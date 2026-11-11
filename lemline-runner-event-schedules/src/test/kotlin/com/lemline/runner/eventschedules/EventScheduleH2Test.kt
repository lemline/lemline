// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventScheduleH2Test : EventScheduleServiceTestBase() {
    override val db = H2TestDatabaseConfig()
    @BeforeAll fun setup() { db.migrate() }
    @AfterAll fun teardown() { db.close() }
}
