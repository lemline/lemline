// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.sqlite

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.testcases.bases.BrokerEventScheduleExecutionTest
import com.lemline.runner.tests.resources.SqliteMessagingTestClient
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteEventScheduleExecutionTest : BrokerEventScheduleExecutionTest() {
    override suspend fun publish(event: String) = SqliteMessagingTestClient().use {
        it.publish("lemline-test-cloudevents", event)
    }
}
