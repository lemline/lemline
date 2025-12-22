// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.listeners

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.definitions.DefinitionRepository
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

/**
 * H2-specific tests for ListenerEventRepository.
 * Uses in-memory H2 database.
 */
@DisplayName("ListenerEventRepository [H2]")
class ListenerEventRepositoryH2Test : ListenerEventRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getEventRepository(): ListenerEventRepository = eventRepository
    override fun getListenerRepository(): ListenerRepository = listenerRepository
    override fun getDefinitionRepository(): DefinitionRepository = definitionRepository

    companion object {
        private val testDb = H2TestDatabaseConfig()
        private val listenerRepository: ListenerRepository by lazy {
            ListenerRepository().apply { databaseConfig = testDb }
        }
        private val eventRepository: ListenerEventRepository by lazy {
            ListenerEventRepository().apply { databaseConfig = testDb }
        }
        private val definitionRepository: DefinitionRepository by lazy {
            DefinitionRepository().apply { databaseConfig = testDb }
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
