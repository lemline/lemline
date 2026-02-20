// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * ListenerRepository tests using H2 in-memory database.
 * These tests always run (no Docker required).
 */
@DisplayName("ListenerRepository [H2]")
class ListenerRepositoryH2Test : ListenerRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): ListenerRepository = repository
    override fun getEventRepository(): ListenerEventRepository = eventRepository

    companion object {
        private val testDb = H2TestDatabaseConfig()

        private val repository: ListenerRepository by lazy {
            ListenerRepository().apply {
                databaseConfig = testDb
            }
        }
        private val eventRepository: ListenerEventRepository by lazy {
            ListenerEventRepository().apply {
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
