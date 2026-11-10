// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

@DisplayName("GatewayCommandOutboxRepository [H2]")
class GatewayCommandRepositoryH2Test : GatewayCommandRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): GatewayCommandRepository = repository

    companion object {
        private val testDb = H2TestDatabaseConfig()

        private val repository: GatewayCommandRepository by lazy {
            GatewayCommandRepository().apply {
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
