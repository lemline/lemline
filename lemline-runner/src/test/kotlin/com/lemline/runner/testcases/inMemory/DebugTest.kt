package com.lemline.runner.testcases.inMemory

import com.lemline.core.testcases.impl.TestMocks.highTemperatureData
import com.lemline.core.testcases.impl.TestMocks.highTemperatureEvent
import com.lemline.core.testcases.impl.TestMocks.orderCreatedCloudEvent
import com.lemline.core.testcases.impl.TestMocks.orderCreatedData
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray

@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class DebugTest : InMemoryWorkflowTest(
    listOf(
        WorkflowTestCase(
            name = "listen all strategy waits for all event types, an event matching multiple filters",
            cloudEvents = listOf(
                orderCreatedCloudEvent,
                highTemperatureEvent,
            ),
            yaml = $$"""
                do:
                  - waitForOrderAndPayment:
                      listen:
                        to:
                          all:
                            - with:
                                type: order.created
                            - with:
                                type: sensor.reading
                            - with:
                                data:  ${ .temperature > 30 }
            """.trimIndent(),
            tags = setOf("listen", "all"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData, highTemperatureData)))
        ),
    )
)
