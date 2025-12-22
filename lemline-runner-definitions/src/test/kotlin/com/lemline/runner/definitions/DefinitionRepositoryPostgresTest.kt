// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * PostgreSQL-specific tests for DefinitionRepository.
 * Uses Testcontainers to spin up a PostgreSQL instance.
 */
@RequiresDocker
@DisplayName("DefinitionRepository [PostgreSQL]")
class DefinitionRepositoryPostgresTest : DefinitionRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): DefinitionRepository = repository

    companion object {
        private val testDb = PostgresTestDatabaseConfig()
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
