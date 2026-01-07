package com.lemline.runner.testcases.inMemory

import com.lemline.core.testcases.impl.TestMocks.eventWithTime
import com.lemline.core.testcases.impl.TestMocks.orderCreatedCloudEvent
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class DebugListenTest : InMemoryWorkflowTest(
    listOf(
        WorkflowTestCase(
            name = "listen can filter by time (literal match)",
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithTime),
            yaml = """
                do:
                  - waitForScheduledTask:
                      listen:
                        to:
                          one:
                            with:
                              time: "2024-06-15T14:30:00Z"
            """.trimIndent(),
            tags = setOf("listen", "filter", "time"),
            validate = expectOutput(JsonArray(listOf(buildJsonObject { put("taskId", "TASK-001") })))
        ),
    )
)
