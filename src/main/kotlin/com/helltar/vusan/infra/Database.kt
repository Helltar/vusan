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
import java.sql.DriverManager
import java.sql.Statement
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

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
                        // WAL lets readers and writers proceed concurrently and sticks once set (it lives in
                        // the file header). busy_timeout makes writers wait instead of failing on contention.
                        // synchronous=NORMAL trims fsyncs without losing committed transactions in WAL mode.
                        stmt.execute("PRAGMA journal_mode=WAL")
                        stmt.execute("PRAGMA busy_timeout=5000")
                        stmt.execute("PRAGMA synchronous=NORMAL")
                    }
                },
            )

            suspendTransaction(newDatabase) { prepareSchema(requestedSpec.absolutePath) }

            database = newDatabase
            connectionSpec = requestedSpec
        }
    }

    /**
     * Creates an empty database at [Schema.VERSION], accepts one already there, moves an older one through
     * [Schema.steps], and refuses the rest.
     *
     * The shape lives in `PRAGMA user_version` in the file header, `0` until stamped. Every step runs in this
     * one transaction with its stamp, so a failure leaves the database as found; a copy is kept beside it
     * first anyway. A version older than the first step, `0` with tables, or newer than this build is left
     * untouched and named in the error: a schema half one version and half another is how rows go missing
     * quietly.
     */
    private fun JdbcTransaction.prepareSchema(path: Path) {
        val found = userVersion()

        if (found == Schema.VERSION) return

        if (found == 0 && isEmpty()) {
            SchemaUtils.create(tables = Schema.tables.toTypedArray())
            stampUserVersion(Schema.VERSION)
            log.info { "created a fresh database at $SCHEMA_LABEL" }
            return
        }

        check(found < Schema.VERSION) {
            "Database at $path is schema version $found, newer than the $SCHEMA_LABEL this build knows. " +
                    "Run the build that matches it."
        }

        check(found >= Schema.oldestStepped) {
            "Database at $path is schema version $found, older than this build moves forward " +
                    "(${Schema.oldestStepped} and later). Move it across by hand into a fresh database and stamp it " +
                    "with `PRAGMA user_version = ${Schema.VERSION}`, or run the build that matches it."
        }

        val copy = keepCopy(path, found)

        for (version in found + 1..Schema.VERSION) {
            Schema.steps.getValue(version).forEach { sql -> rawStatement { it.execute(sql) } }
            stampUserVersion(version)
        }

        log.info { "moved the database from schema version $found to ${Schema.VERSION}, the old one kept at $copy" }
    }

    // VACUUM INTO cannot run in a transaction, so the copy uses its own connection; it folds the WAL in,
    // which copying the file would miss. a copy left by an earlier failed start is the same database.
    private fun keepCopy(path: Path, version: Int): Path {
        val copy = path.resolveSibling("${path.fileName}.schema-$version")

        if (!copy.exists()) {
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use {
                    it.execute("VACUUM INTO '${copy.toString().replace("'", "''")}'")
                }
            }
        }

        return copy
    }

    // through the transaction's own connection rather than `exec`: sqlite takes a pragma write only from a
    // plain statement and silently ignores Exposed's prepared one, which would leave every start thinking the
    // database was never stamped.
    private fun JdbcTransaction.userVersion(): Int =
        rawStatement { statement ->
            statement.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 }
        }

    private fun JdbcTransaction.stampUserVersion(version: Int) {
        // the value is this build's own constant, never input
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

    // exposed's JDBC suspendTransaction runs blocking driver calls on the caller's dispatcher; IO keeps a
    // contended write (busy_timeout up to 5s) from stalling the polling or agent coroutines.
    suspend fun <T> dbTransaction(block: suspend JdbcTransaction.() -> T): T {
        val currentDatabase = checkNotNull(database) { "Database is not connected" }

        return withContext(Dispatchers.IO) { suspendTransaction(currentDatabase) { block() } }
    }
}
