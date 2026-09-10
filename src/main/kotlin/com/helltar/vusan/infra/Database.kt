package com.helltar.vusan.infra

import com.helltar.vusan.config.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import kotlin.io.path.createDirectories

private data class DbConnectionSpec(val url: String, val absolutePath: Path) {

    companion object {
        fun fromConfig(config: AppConfig): DbConnectionSpec {
            val absolutePath = Path.of(config.databasePath).toAbsolutePath().normalize()
            return DbConnectionSpec(url = "jdbc:sqlite:$absolutePath", absolutePath = absolutePath)
        }
    }
}

object Db {
    private const val SCHEMA_LABEL = "schema version ${Schema.VERSION}"

    private val log = KotlinLogging.logger {}
    private val connectMutex = Mutex()
    private var database: Database? = null
    private var connectionSpec: DbConnectionSpec? = null

    suspend fun connect(config: AppConfig) {
        val requestedSpec = DbConnectionSpec.fromConfig(config)

        connectMutex.withLock {
            val currentDatabase = database

            if (currentDatabase != null) {
                val currentSpec = checkNotNull(connectionSpec)

                check(currentSpec == requestedSpec) {
                    "Database is already connected to ${currentSpec.absolutePath}, " +
                            "refusing reconnect to ${requestedSpec.absolutePath}"
                }

                return
            }

            requestedSpec.absolutePath.parent?.createDirectories()

            val newDatabase = Database.connect(
                url = requestedSpec.url,
                setupConnection = { connection ->
                    connection.createStatement().use { stmt ->
                        // database WAL mode lets readers and writers proceed concurrently and is sticky once set
                        // (stored in the DB file header). busy_timeout makes writers wait instead of
                        // failing fast on contention. synchronous=NORMAL trims redundant fsyncs in WAL
                        // mode without losing durability for committed transactions.
                        stmt.execute("PRAGMA journal_mode=WAL")
                        stmt.execute("PRAGMA busy_timeout=5000")
                        stmt.execute("PRAGMA synchronous=NORMAL")
                    }
                }
            )

            suspendTransaction(newDatabase) { prepareSchema(requestedSpec.absolutePath) }

            database = newDatabase
            connectionSpec = requestedSpec
        }
    }

    /**
     * Brings the connected database to [Schema.VERSION], or refuses to work with it.
     *
     * A database says what shape it has in `PRAGMA user_version`, which SQLite keeps in the file header
     * and hands back as `0` until something stamps it. So `0` with tables already in it is a database
     * from before this was versioned at all: it is left untouched and named in the error, because
     * guessing at a schema is how rows go missing quietly.
     */
    private fun JdbcTransaction.prepareSchema(path: Path) {
        val found = userVersion()

        check(found <= Schema.VERSION) {
            "Database at $path is schema version $found, newer than the " +
                    "$SCHEMA_LABEL this build knows. Run the build that wrote it."
        }

        if (found == Schema.VERSION) return

        if (found == 0) {
            check(isEmpty()) {
                "Database at $path predates versioned schemas and cannot be " +
                        "reconciled from the declarations. Move it across by hand (docs/database.md), " +
                        "then stamp it with `PRAGMA user_version = ${Schema.VERSION}`."
            }

            SchemaUtils.create(tables = Schema.tables.toTypedArray())
            stampUserVersion(Schema.VERSION)
            log.info { "created a fresh database at $SCHEMA_LABEL" }
            return
        }

        for (version in (found + 1)..Schema.VERSION) {
            val migration =
                checkNotNull(Schema.migrations.singleOrNull { it.to == version }) {
                    "No migration to schema version $version; this build cannot open a database at $found"
                }

            migration.apply(this)
            stampUserVersion(version)
            log.info { "migrated the database to schema version $version" }
        }
    }

    // the version pragmas go through the transaction's own connection rather than `exec`: sqlite takes a
    // pragma write only from a plain statement, and silently does nothing with the prepared one Exposed
    // sends — which would leave every start believing the database had never been stamped.
    private fun JdbcTransaction.userVersion(): Int =
        rawStatement { statement ->
            statement.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 }
        }

    private fun JdbcTransaction.stampUserVersion(version: Int) {
        // the value is this build's own constant, never anything read in
        rawStatement { it.execute("PRAGMA user_version = $version") }
    }

    private fun JdbcTransaction.isEmpty(): Boolean =
        rawStatement { statement ->
            statement
                .executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
                .use { !it.next() || it.getInt(1) == 0 }
        }

    private fun <T> JdbcTransaction.rawStatement(block: (Statement) -> T): T =
        (connection.connection as Connection).createStatement().use(block)

    suspend fun disconnect() {
        connectMutex.withLock {
            val currentDatabase = database ?: return
            TransactionManager.closeAndUnregister(currentDatabase)
            database = null
            connectionSpec = null
        }
    }

    // exposed's JDBC suspendTransaction runs the blocking driver calls on the caller's dispatcher;
    // hop to Dispatchers.IO so a contended SQLite write (busy_timeout up to 5s) never stalls the
    // long-polling or agent coroutines.
    suspend fun <T> dbTransaction(block: suspend JdbcTransaction.() -> T): T {
        val currentDatabase = checkNotNull(database) { "Database is not connected" }
        return withContext(Dispatchers.IO) { suspendTransaction(currentDatabase) { block() } }
    }
}
