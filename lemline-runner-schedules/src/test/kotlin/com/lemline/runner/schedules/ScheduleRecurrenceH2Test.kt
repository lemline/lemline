// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.runner.common.test.H2TestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

class ScheduleRecurrenceH2Test : ScheduleRecurrenceTestBase() {
    override fun database() = testDb
    override fun repository() = repo

    companion object {
        private val testDb = H2TestDatabaseConfig()
        private val repo by lazy { ScheduleRepository().apply { databaseConfig = testDb } }

        @BeforeAll
        @JvmStatic
        fun setup() {
            testDb.migrate()
        }

        @AfterAll
        @JvmStatic
        fun teardown() { testDb.close() }
    }
}
