// SPDX-License-Identifier: BUSL-1.1

package com.lemline.runner.waits

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * WaitRepository tests using PostgreSQL via Testcontainers.
 * These tests are skipped if Docker is not available.
 */
@RequiresDocker
@DisplayName("WaitRepository [PostgreSQL]")
class WaitRepositoryPostgresTest : WaitRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): WaitRepository = repository

    companion object {
        private val testDb = PostgresTestDatabaseConfig()

        private val repository: WaitRepository by lazy {
            WaitRepository().apply {
                databaseConfig = testDb
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
