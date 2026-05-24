package com.helltar.vusan.infra

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.infra.tables.ChatMessagesTable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                    "Database is already connected to ${currentSpec.absolutePath}, refusing reconnect to ${requestedSpec.absolutePath}"
                }

                return
            }

            requestedSpec.absolutePath.parent?.createDirectories()

            val newDatabase = Database.connect(
                url = requestedSpec.url,
                setupConnection = { connection ->
                    connection.createStatement().use { stmt ->
                        // WAL lets readers and writers proceed concurrently and is sticky once set
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
                SchemaUtils.create(ChatMessagesTable)
            }

            database = newDatabase
            connectionSpec = requestedSpec

            Runtime.getRuntime().addShutdownHook(Thread { runBlocking { disconnect() } })
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

    suspend fun <T> dbTransaction(block: suspend JdbcTransaction.() -> T): T {
        val currentDatabase = checkNotNull(database) { "Database is not connected" }
        return suspendTransaction(currentDatabase) { block() }
    }
}
