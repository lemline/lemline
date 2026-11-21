// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.failures

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.SQLiteTestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

@DisplayName("FailureRepository [SQLite]")
class FailureRepositorySQLiteTest : FailureRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): FailureRepository = repository

    companion object {
        private val testDb = SQLiteTestDatabaseConfig()

        private val repository: FailureRepository by lazy {
            FailureRepository().apply {
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
