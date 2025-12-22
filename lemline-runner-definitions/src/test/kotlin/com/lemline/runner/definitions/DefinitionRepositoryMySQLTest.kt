// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.MySQLTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * MySQL-specific tests for DefinitionRepository.
 * Uses Testcontainers to spin up a MySQL instance.
 */
@RequiresDocker
@DisplayName("DefinitionRepository [MySQL]")
class DefinitionRepositoryMySQLTest : DefinitionRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): DefinitionRepository = repository

    companion object {
        private val testDb = MySQLTestDatabaseConfig()
        private val repository: DefinitionRepository by lazy {
            DefinitionRepository().apply { databaseConfig = testDb }
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            testDb.start()
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            testDb.close()
        }
    }
}
