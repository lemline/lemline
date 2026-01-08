// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.schedules

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.MySQLTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName

@RequiresDocker
@DisplayName("ScheduleRepository [MySQL]")
class ScheduleRepositoryMySQLTest : ScheduleRepositoryTestBase() {

    override fun getDatabaseConfig(): DatabaseConfig = testDb
    override fun getRepository(): ScheduleRepository = repository

    companion object {
        private val testDb = MySQLTestDatabaseConfig()

        private val repository: ScheduleRepository by lazy {
            ScheduleRepository().apply {
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
