// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.SQLiteDatabase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway

class SQLiteTestDatabaseConfig : OrchestrationTestDatabase {
    override val dbType = DatabaseType.SQLITE

    private val directory = Files.createTempDirectory("lemline-sqlite-test-")
    val path = directory.resolve("orchestration.db")

    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = SQLiteDatabase.jdbcUrl(path)
        driverClassName = "org.sqlite.JDBC"
        maximumPoolSize = 5
        minimumIdle = 0
    })

    override fun migrate() {
        pool.connection.use(SQLiteDatabase::initialize)
        Flyway.configure().dataSource(pool).locations("classpath:db/migration/sqlite").load().migrate()
    }

    override suspend fun <R> withConnection(
        connection: Connection?,
        block: suspend (Connection) -> R,
    ): R = withContext(Dispatchers.IO) {
        if (connection == null) pool.connection.use { block(it) } else block(connection)
    }

    override suspend fun <R> withTransaction(
        connection: Connection?,
        block: suspend (Connection) -> R,
    ): R = withConnection(connection) {
        if (connection == null) SQLiteDatabase.writeTransaction(it, block) else block(it)
    }

    override fun close() {
        pool.close()
        directory.toFile().deleteRecursively()
    }
}
