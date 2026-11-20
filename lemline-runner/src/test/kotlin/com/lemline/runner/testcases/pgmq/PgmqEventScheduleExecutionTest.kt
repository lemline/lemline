// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.pgmq

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.testcases.bases.BrokerEventScheduleExecutionTest
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqEventScheduleExecutionTest : BrokerEventScheduleExecutionTest() {
    @Inject lateinit var database: DatabaseManager

    override suspend fun publish(event: String) {
        database.withConnection { conn ->
            conn.prepareStatement("SELECT pgmq.send(?, ?::jsonb)").use {
                it.setString(1, "lemline-test-cloudevents")
                it.setString(2, event)
                it.execute()
            }
        }
    }
}
