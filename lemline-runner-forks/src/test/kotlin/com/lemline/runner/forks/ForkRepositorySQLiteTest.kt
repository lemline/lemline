// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.SQLiteTestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * H2-specific tests for ForkRepository.
 * Uses in-memory H2 database.
 */
@DisplayName("ForkRepository [SQLite]")
class ForkRepositorySQLiteTest : ForkRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): ForkRepository = repository

    companion object {
        private val testDb = SQLiteTestDatabaseConfig()
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
