// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

@RequiresDocker
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventSchedulePostgresTest : EventScheduleServiceTestBase() {
    override val db = PostgresTestDatabaseConfig()
    @BeforeAll fun setup() { db.start(); db.migrate() }
    @AfterAll fun teardown() { db.close() }
}
