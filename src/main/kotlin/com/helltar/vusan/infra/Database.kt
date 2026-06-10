package com.helltar.vusan.infra

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.infra.tables.ChatMessagesTable
import com.helltar.vusan.infra.tables.MemoryTable
import com.helltar.vusan.infra.tables.PeersTable
import com.helltar.vusan.infra.tables.ScheduledTasksTable
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

            suspendTransaction(newDatabase) {
                SchemaUtils.create(ChatMessagesTable, ScheduledTasksTable, MemoryTable, PeersTable)
            }

            database = newDatabase
            connectionSpec = requestedSpec
        }
    }

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
