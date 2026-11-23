// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.messaging.base.WorkflowConsumerTest
import com.lemline.runner.tests.profiles.SqliteProfile
import com.lemline.runner.tests.resources.SqliteMessagingTestClient
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteProfile::class)
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class WorkflowConsumerSqliteTest : WorkflowConsumerTest() {
    private val client by lazy { SqliteMessagingTestClient() }

    override fun setupMessaging() = client.clearQueues(
        "lemline-test-in-commands", "lemline-test-in-events", "lemline-test-commands", "lemline-test-events",
    )

    override fun cleanupMessaging() = Unit

    override fun sendCommand(message: String) = runBlocking { client.publish("lemline-test-in-commands", message) }

    override suspend fun receiveCommand(timeout: Long, unit: TimeUnit) =
        client.receive("lemline-test-commands", unit.toMillis(timeout))

    override suspend fun receiveEvent(timeout: Long, unit: TimeUnit) =
        client.receive("lemline-test-events", unit.toMillis(timeout))

    @AfterAll
    fun close() = client.close()
}
