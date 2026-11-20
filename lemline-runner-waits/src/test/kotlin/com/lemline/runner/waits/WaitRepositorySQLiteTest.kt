// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.waits

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.SQLiteTestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * WaitRepository tests using H2 in-memory database.
 * These tests always run (no Docker required).
 */
@DisplayName("WaitRepository [SQLite]")
class WaitRepositorySQLiteTest : WaitRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): WaitRepository = repository

    companion object {
        private val testDb = SQLiteTestDatabaseConfig()

        private val repository: WaitRepository by lazy {
            WaitRepository().apply {
                databaseConfig = testDb
            }
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            testDb.close()
        }
    }
}
