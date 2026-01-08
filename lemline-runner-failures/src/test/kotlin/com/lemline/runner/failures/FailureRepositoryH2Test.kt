// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.failures

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

@DisplayName("FailureRepository [H2]")
class FailureRepositoryH2Test : FailureRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): FailureRepository = repository

    companion object {
        private val testDb = H2TestDatabaseConfig()

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
