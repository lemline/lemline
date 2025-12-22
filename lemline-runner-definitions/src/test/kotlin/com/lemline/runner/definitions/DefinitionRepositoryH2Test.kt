// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * H2-specific tests for DefinitionRepository.
 * Uses in-memory H2 database.
 */
@DisplayName("DefinitionRepository [H2]")
class DefinitionRepositoryH2Test : DefinitionRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): DefinitionRepository = repository

    companion object {
        private val testDb = H2TestDatabaseConfig()
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
