// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.runner.common.test.SQLiteTestDatabaseConfig
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventScheduleSQLiteTest : EventScheduleServiceTestBase() {
    override val db = SQLiteTestDatabaseConfig()
    @BeforeAll fun setup() { db.migrate() }
    @AfterAll fun teardown() { db.close() }
}
