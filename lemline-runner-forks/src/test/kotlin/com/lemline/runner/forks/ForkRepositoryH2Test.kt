// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * H2-specific tests for ForkRepository.
 * Uses in-memory H2 database.
 */
@DisplayName("ForkRepository [H2]")
class ForkRepositoryH2Test : ForkRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): ForkRepository = repository

    companion object {
        private val testDb = H2TestDatabaseConfig()
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
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            testDb.close()
        }
    }
}
