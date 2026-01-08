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
 * PostgreSQL-specific tests for ForkBranchRepository.
 * Uses Testcontainers to spin up a PostgreSQL instance.
 */
@RequiresDocker
@DisplayName("ForkBranchRepository [PostgreSQL]")
class ForkBranchRepositoryPostgresTest : ForkBranchRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getForkRepository(): ForkRepository = forkRepository
    override fun getBranchRepository(): ForkBranchRepository = branchRepository

    companion object {
        private val testDb = PostgresTestDatabaseConfig()
        private val forkRepository: ForkRepository by lazy {
            ForkRepository().apply { databaseConfig = testDb }
        }
        private val branchRepository: ForkBranchRepository by lazy {
            ForkBranchRepository().apply { databaseConfig = testDb }
        }

        @BeforeAll
        @JvmStatic
        fun setupClass() {
            testDb.start()
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardownClass() {
            testDb.close()
        }
    }
}
