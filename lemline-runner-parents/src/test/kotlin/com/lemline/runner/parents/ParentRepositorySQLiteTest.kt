// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.SQLiteTestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

@DisplayName("ParentRepository [SQLite]")
class ParentRepositorySQLiteTest : ParentRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): ParentRepository = repository

    companion object {
        private val testDb = SQLiteTestDatabaseConfig()

        private val repository: ParentRepository by lazy {
            ParentRepository().apply {
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
