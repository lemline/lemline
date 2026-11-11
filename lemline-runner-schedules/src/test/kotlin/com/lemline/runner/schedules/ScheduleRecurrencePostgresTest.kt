// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

@RequiresDocker
class ScheduleRecurrencePostgresTest : ScheduleRecurrenceTestBase() {
    override fun database() = testDb
    override fun repository() = repo

    companion object {
        private val testDb = PostgresTestDatabaseConfig()
        private val repo by lazy { ScheduleRepository().apply { databaseConfig = testDb } }

        @BeforeAll
        @JvmStatic
        fun setup() {
            testDb.start()
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardown() { testDb.close() }
    }
}
