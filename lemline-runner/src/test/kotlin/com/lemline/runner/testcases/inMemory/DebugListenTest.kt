package com.lemline.runner.testcases.inMemory

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
            name = "listen one with foreach processes event through nested tasks",
            cloudEvents = listOf(orderCreatedCloudEvent),
            yaml = $$"""
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                      foreach:
                        do:
                          - processOrder:
                              set:
                                processed: true
                                orderId: ${ .orderId }
            """.trimIndent(),
            tags = setOf("listen", "foreach", "one"),
            validate = expectOutput(
                JsonArray(listOf(buildJsonObject {
                    put("processed", true)
                    put("orderId", "ORD-12345")
                }))
            )
        ),
    )
)
