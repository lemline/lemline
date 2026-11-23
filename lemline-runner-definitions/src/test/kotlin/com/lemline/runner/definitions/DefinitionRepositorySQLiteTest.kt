// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.SQLiteTestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * SQLite-specific tests for DefinitionRepository.
 * Uses a temporary SQLite database file.
 */
@DisplayName("DefinitionRepository [SQLite]")
class DefinitionRepositorySQLiteTest : DefinitionRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): DefinitionRepository = repository

    companion object {
        private val testDb = SQLiteTestDatabaseConfig()
        private val repository: DefinitionRepository by lazy {
            DefinitionRepository().apply { databaseConfig = testDb }
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
