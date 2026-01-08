// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.forks

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * PostgreSQL-specific tests for ForkRepository.
 * Uses Testcontainers to spin up a PostgreSQL instance.
 */
@RequiresDocker
@DisplayName("ForkRepository [PostgreSQL]")
class ForkRepositoryPostgresTest : ForkRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): ForkRepository = repository

    companion object {
        private val testDb = PostgresTestDatabaseConfig()
        private val branchRepository: ForkBranchRepository by lazy {
            ForkBranchRepository().apply { databaseConfig = testDb }
        }
        private val repository: ForkRepository by lazy {
            ForkRepository().apply {
                databaseConfig = testDb
                forkBranchRepository = branchRepository
            }
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
