// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

@RequiresDocker
@DisplayName("GatewayCommandOutboxRepository [PostgreSQL]")
class GatewayCommandOutboxRepositoryPostgresTest : GatewayCommandOutboxRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): GatewayCommandOutboxRepository = repository

    companion object {
        private val testDb = PostgresTestDatabaseConfig()

        private val repository: GatewayCommandOutboxRepository by lazy {
            GatewayCommandOutboxRepository().apply {
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
