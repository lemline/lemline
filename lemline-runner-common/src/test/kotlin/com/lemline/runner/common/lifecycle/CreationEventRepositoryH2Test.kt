// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

import com.lemline.runner.common.config.DatabaseConfig

import com.lemline.runner.common.test.H2TestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

@DisplayName("CreationEventRepository [H2]")
class CreationEventRepositoryH2Test : CreationEventRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): CreationEventRepository = repository

    companion object {
        private val testDb = H2TestDatabaseConfig()

        private val repository: CreationEventRepository by lazy {
            CreationEventRepository().apply { databaseConfig = testDb }
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
