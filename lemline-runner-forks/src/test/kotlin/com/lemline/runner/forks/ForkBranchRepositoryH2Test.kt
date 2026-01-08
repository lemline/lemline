// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.forks

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * H2-specific tests for ForkBranchRepository.
 * Uses in-memory H2 database.
 */
@DisplayName("ForkBranchRepository [H2]")
class ForkBranchRepositoryH2Test : ForkBranchRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getForkRepository(): ForkRepository = forkRepository
    override fun getBranchRepository(): ForkBranchRepository = branchRepository

    companion object {
        private val testDb = H2TestDatabaseConfig()
        private val forkRepository: ForkRepository by lazy {
            ForkRepository().apply { databaseConfig = testDb }
        }
        private val branchRepository: ForkBranchRepository by lazy {
            ForkBranchRepository().apply { databaseConfig = testDb }
        }

        @BeforeAll
        @JvmStatic
        fun setupClass() {
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardownClass() {
            testDb.close()
        }
    }
}
