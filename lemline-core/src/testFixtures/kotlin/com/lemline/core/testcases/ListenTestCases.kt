// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for listen task execution.
 * Tests CloudEvent listening using real CloudEvents emitted to InMemoryCloudEventHook.
 */
object ListenTestCases {

    val cases = listOf(
        // ─────────────────────────────────────────────────────────────────────────
        // Basic Listen Tests
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can wait for a single event",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = """
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
            """.trimIndent(),
            tags = setOf("listen", "cloudevents"),
            validate = expectOutputMatching("array with order event data") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("orderId")?.jsonPrimitive?.contentOrNull == "ORD-12345"
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by event type",
            cloudEvents = listOf(TestMocks.userRegisteredCloudEvent),
            yaml = """
                do:
                  - waitForUser:
                      listen:
                        to:
                          one:
                            with:
                              type: user.registered
            """.trimIndent(),
            tags = setOf("listen", "cloudevents"),
            validate = expectOutputMatching("array with user registration data") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("userId")?.jsonPrimitive?.contentOrNull == "USR-98765" &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("email")?.jsonPrimitive?.contentOrNull == "user@example.com"
            }
        ),

        WorkflowTestCase(
            name = "listen can use wildcard event filter",
            cloudEvents = listOf(TestMocks.genericCloudEvent),
            yaml = """
                do:
                  - waitForAnyEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: some.unknown.type
            """.trimIndent(),
            tags = setOf("listen", "cloudevents"),
            validate = expectOutputMatching("array with generic event data (wildcard match)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "generic"
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Listen with Output Transformation
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen result can be transformed with output as",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = $$"""
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                      output:
                        as: '${ .[0].data | {id: .orderId, customer: .customerId} }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents"),
            validate = expectOutputMatching("transformed order with id and customer") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["id"]?.jsonPrimitive?.contentOrNull == "ORD-12345" &&
                    obj["customer"]?.jsonPrimitive?.contentOrNull == "CUST-001"
            }
        ),

        WorkflowTestCase(
            name = "listen can extract nested data from event",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = $$"""
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                      output:
                        as: '${ .[0].data.items[0] }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents"),
            validate = expectOutputMatching("first item from order") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["productId"]?.jsonPrimitive?.contentOrNull == "PROD-001" &&
                    obj["quantity"]?.jsonPrimitive?.content == "2"
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Listen in Workflow Steps
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can be chained with other tasks",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = $$"""
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                  - processOrder:
                      set:
                        orderId: ${ .[0].data.orderId }
                        processed: true
            """.trimIndent(),
            tags = setOf("listen", "cloudevents"),
            validate = expectOutputMatching("processed order result") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["orderId"]?.jsonPrimitive?.contentOrNull == "ORD-12345" &&
                    obj["processed"]?.jsonPrimitive?.content == "true"
            }
        ),

        WorkflowTestCase(
            name = "listen output can be used in subsequent http call",
            mockConfig = TestMocks.httpConfig,
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = $$"""
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                      output:
                        as: '${ .[0].data }'
                  - fetchDetails:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
            """.trimIndent(),
            tags = setOf("listen", "http", "cloudevents"),
            validate = expectOutputMatching("post data from http call") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("id") && obj.containsKey("title")
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Listen for Sensor/IoT Events
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can receive sensor reading events",
            cloudEvents = listOf(TestMocks.sensorReadingCloudEvent),
            yaml = """
                do:
                  - waitForReading:
                      listen:
                        to:
                          one:
                            with:
                              type: sensor.reading
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "iot"),
            validate = expectOutputMatching("sensor reading with temperature and humidity") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("sensorId")?.jsonPrimitive?.contentOrNull == "SENSOR-001" &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("temperature")?.jsonPrimitive?.double == 23.5 &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("humidity")?.jsonPrimitive?.content == "65"
            }
        ),

        WorkflowTestCase(
            name = "listen can process sensor data with transformation",
            cloudEvents = listOf(TestMocks.sensorReadingCloudEvent),
            yaml = $$"""
                do:
                  - waitForReading:
                      listen:
                        to:
                          one:
                            with:
                              type: sensor.reading
                      output:
                        as: '${ .[0].data | {sensor: .sensorId, tempF: ((.temperature * 9/5) + 32)} }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "iot"),
            validate = expectOutputMatching("sensor data with Fahrenheit conversion") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["sensor"]?.jsonPrimitive?.contentOrNull == "SENSOR-001" &&
                    obj["tempF"]?.jsonPrimitive?.double?.let { it > 74 && it < 75 } == true
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Listen with Conditional Flow
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen result can be used in switch condition",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = $$"""
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                      output:
                        as: '${ .[0].data }'
                  - checkTotal:
                      switch:
                        - highValue:
                            when: ${ .total > 50 }
                            then: continue
                  - markHighValue:
                      set:
                        highValueOrder: true
                        total: ${ .total }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "switch"),
            validate = expectOutputMatching("high value order marker") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["highValueOrder"]?.jsonPrimitive?.content == "true" &&
                    obj["total"]?.jsonPrimitive?.double == 99.99
            }
        )

        // Note: Listen + foreach tests are excluded for now as they require
        // additional infrastructure support (ListenForEachCompleted handling)
        // that involves more complex state management beyond mock configuration.
    )
}
