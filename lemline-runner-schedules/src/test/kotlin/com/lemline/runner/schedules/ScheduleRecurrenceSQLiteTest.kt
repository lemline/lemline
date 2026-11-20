// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.runner.common.test.SQLiteTestDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

class ScheduleRecurrenceSQLiteTest : ScheduleRecurrenceTestBase() {
    override fun database() = testDb
    override fun repository() = repo

    companion object {
        private val testDb = SQLiteTestDatabaseConfig()
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
