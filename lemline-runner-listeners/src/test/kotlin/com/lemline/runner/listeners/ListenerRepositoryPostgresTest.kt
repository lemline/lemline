// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.listeners

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * PostgreSQL-specific tests for ListenerRepository.
 * Uses Testcontainers to spin up a PostgreSQL instance.
 */
@RequiresDocker
@DisplayName("ListenerRepository [PostgreSQL]")
class ListenerRepositoryPostgresTest : ListenerRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): ListenerRepository = repository
    override fun getEventRepository(): ListenerEventRepository = eventRepository

    companion object {
        private val testDb = PostgresTestDatabaseConfig()
        private val repository: ListenerRepository by lazy {
            ListenerRepository().apply { databaseConfig = testDb }
        }
        private val eventRepository: ListenerEventRepository by lazy {
            ListenerEventRepository().apply { databaseConfig = testDb }
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
