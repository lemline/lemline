// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.MySQLTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * MySQL-specific tests for ForkRepository.
 * Uses Testcontainers to spin up a MySQL instance.
 */
@RequiresDocker
@DisplayName("ForkRepository [MySQL]")
class ForkRepositoryMySQLTest : ForkRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): ForkRepository = repository

    companion object {
        private val testDb = MySQLTestDatabaseConfig()
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
