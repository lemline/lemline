// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
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
                    arr[0].jsonObject["orderId"]?.jsonPrimitive?.contentOrNull == "ORD-12345"
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
                    arr[0].jsonObject["userId"]?.jsonPrimitive?.contentOrNull == "USR-98765" &&
                    arr[0].jsonObject["email"]?.jsonPrimitive?.contentOrNull == "user@example.com"
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
                    arr[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull == "generic"
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
                        as: '${ .[0] | {id: .orderId, customer: .customerId} }'
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
                        as: '${ .[0].items[0] }'
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
                        orderId: ${ .[0].orderId }
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
                        as: '${ .[0] }'
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
                    arr[0].jsonObject["sensorId"]?.jsonPrimitive?.contentOrNull == "SENSOR-001" &&
                    arr[0].jsonObject["temperature"]?.jsonPrimitive?.double == 23.5 &&
                    arr[0].jsonObject["humidity"]?.jsonPrimitive?.content == "65"
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
                        as: '${ .[0] | {sensor: .sensorId, tempF: ((.temperature * 9/5) + 32)} }'
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
                        as: '${ .[0] }'
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
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by ID (exact string match)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by event id (exact match)",
            cloudEvents = listOf(TestMocks.eventWithSpecificId),
            yaml = """
                do:
                  - waitForSpecificEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: test.event
                              id: specific-event-id-12345
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "id"),
            validate = expectOutputMatching("event with specific id") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["message"]?.jsonPrimitive?.contentOrNull == "test"
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by Source (literal and expression)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by source (literal URI match)",
            cloudEvents = listOf(TestMocks.eventWithSpecificSource),
            yaml = """
                do:
                  - waitForOrdersEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: test.event
                              source: https://orders.example.com/api
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "source"),
            validate = expectOutputMatching("event from orders source") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["message"]?.jsonPrimitive?.contentOrNull == "from-orders"
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by source with expression (startswith via slice)",
            cloudEvents = listOf(TestMocks.eventWithSpecificSource),
            yaml = $$"""
                do:
                  - waitForOrdersEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: test.event
                              source: '${ .[0:14] == "https://orders" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "source", "expression"),
            validate = expectOutputMatching("event from orders source (startswith)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["message"]?.jsonPrimitive?.contentOrNull == "from-orders"
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by source with expression (contains)",
            cloudEvents = listOf(TestMocks.eventWithSpecificSource),
            yaml = $$"""
                do:
                  - waitForOrdersEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: test.event
                              source: '${ contains("orders.example.com") }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "source", "expression"),
            validate = expectOutputMatching("event from orders source (contains)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["message"]?.jsonPrimitive?.contentOrNull == "from-orders"
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by Subject (exact string match)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by subject (exact match)",
            cloudEvents = listOf(TestMocks.eventWithSubject),
            yaml = """
                do:
                  - waitForOrderShipped:
                      listen:
                        to:
                          one:
                            with:
                              type: order.shipped
                              subject: order/ORD-999
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "subject"),
            validate = expectOutputMatching("event with specific subject") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["orderId"]?.jsonPrimitive?.contentOrNull == "ORD-999" &&
                    arr[0].jsonObject["carrier"]?.jsonPrimitive?.contentOrNull == "FedEx"
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by Time (literal and expression)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by time (literal match)",
            cloudEvents = listOf(TestMocks.eventWithTime),
            yaml = """
                do:
                  - waitForScheduledTask:
                      listen:
                        to:
                          one:
                            with:
                              type: scheduled.task
                              time: "2024-06-15T14:30:00Z"
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "time"),
            validate = expectOutputMatching("event with specific time") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["taskId"]?.jsonPrimitive?.contentOrNull == "TASK-001"
            }
        ),

        // NOTE: Time expression tests (e.g., time: '${ contains("2024") }') are not supported
        // because the Serverless Workflow SDK uses JSON Schema Draft 2020-12 where format
        // validation is annotation-only by default. This causes oneOf validation to fail
        // with "2 are valid" because format:date-time doesn't actually reject runtime
        // expressions. Once the SDK enables format assertions, these tests can be added back.

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by DataContentType (exact string match)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by datacontenttype (application/json)",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = """
                do:
                  - waitForJsonEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                              datacontenttype: application/json
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "datacontenttype"),
            validate = expectOutputMatching("event with json content type") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["orderId"]?.jsonPrimitive?.contentOrNull == "ORD-12345"
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by datacontenttype (application/xml)",
            cloudEvents = listOf(TestMocks.eventWithXmlContentType),
            yaml = """
                do:
                  - waitForXmlEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: xml.data
                              datacontenttype: application/xml
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "datacontenttype"),
            validate = expectOutputMatching("event with xml content type") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["format"]?.jsonPrimitive?.contentOrNull == "xml"
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by DataSchema (literal and expression)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by dataschema (literal URI match)",
            cloudEvents = listOf(TestMocks.eventWithDataSchema),
            yaml = """
                do:
                  - waitForValidatedEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: validated.event
                              dataschema: https://schemas.example.com/person/v1
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "dataschema"),
            validate = expectOutputMatching("event with specific dataschema") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["name"]?.jsonPrimitive?.contentOrNull == "John" &&
                    arr[0].jsonObject["age"]?.jsonPrimitive?.content == "30"
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by dataschema with expression (contains)",
            cloudEvents = listOf(TestMocks.eventWithDataSchema),
            yaml = $$"""
                do:
                  - waitForPersonSchema:
                      listen:
                        to:
                          one:
                            with:
                              type: validated.event
                              dataschema: '${ contains("person") }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "dataschema", "expression"),
            validate = expectOutputMatching("event with person schema (expression)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["name"]?.jsonPrimitive?.contentOrNull == "John"
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by Data (expression evaluated against event payload)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by data expression (temperature threshold)",
            cloudEvents = listOf(TestMocks.highTemperatureEvent),
            yaml = $$"""
                do:
                  - waitForHighTemp:
                      listen:
                        to:
                          one:
                            with:
                              type: sensor.reading
                              data: '${ .temperature > 40 }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "data", "expression"),
            validate = expectOutputMatching("high temperature event") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["sensorId"]?.jsonPrimitive?.contentOrNull == "TEMP-001" &&
                    arr[0].jsonObject["temperature"]?.jsonPrimitive?.double == 42.5
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression (string equality)",
            cloudEvents = listOf(TestMocks.criticalAlertEvent),
            yaml = $$"""
                do:
                  - waitForCriticalAlert:
                      listen:
                        to:
                          one:
                            with:
                              type: alert.triggered
                              data: '${ .severity == "critical" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "data", "expression"),
            validate = expectOutputMatching("critical alert event") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["alertId"]?.jsonPrimitive?.contentOrNull == "ALERT-001" &&
                    arr[0].jsonObject["severity"]?.jsonPrimitive?.contentOrNull == "critical"
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression (complex condition with and)",
            cloudEvents = listOf(TestMocks.highTemperatureEvent),
            yaml = $$"""
                do:
                  - waitForHighTempCelsius:
                      listen:
                        to:
                          one:
                            with:
                              type: sensor.reading
                              data: '${ .temperature > 40 and .unit == "celsius" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "data", "expression"),
            validate = expectOutputMatching("high temperature in celsius") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["temperature"]?.jsonPrimitive?.double == 42.5 &&
                    arr[0].jsonObject["unit"]?.jsonPrimitive?.contentOrNull == "celsius"
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression (or condition)",
            cloudEvents = listOf(TestMocks.criticalAlertEvent),
            yaml = $$"""
                do:
                  - waitForImportantAlert:
                      listen:
                        to:
                          one:
                            with:
                              type: alert.triggered
                              data: '${ .severity == "critical" or .severity == "high" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "data", "expression"),
            validate = expectOutputMatching("important alert (critical or high)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["severity"]?.jsonPrimitive?.contentOrNull == "critical"
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression (nested field access)",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = $$"""
                do:
                  - waitForLargeOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                              data: '${ .total > 50 and .currency == "USD" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "data", "expression"),
            validate = expectOutputMatching("large USD order") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["total"]?.jsonPrimitive?.double == 99.99
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Combined Filters (multiple attributes)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can combine type and source filters",
            cloudEvents = listOf(TestMocks.eventWithSpecificSource),
            yaml = $$"""
                do:
                  - waitForSpecificSourceEvent:
                      listen:
                        to:
                          one:
                            with:
                              type: test.event
                              source: '${ .[0:14] == "https://orders" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "combined"),
            validate = expectOutputMatching("event matching type and source") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty()
            }
        ),

        WorkflowTestCase(
            name = "listen can combine type, source and data filters",
            cloudEvents = listOf(TestMocks.highTemperatureEvent),
            yaml = $$"""
                do:
                  - waitForCriticalReading:
                      listen:
                        to:
                          one:
                            with:
                              type: sensor.reading
                              source: '${ .[0:12] == "https://test" }'
                              data: '${ .temperature > 40 }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "combined"),
            validate = expectOutputMatching("event matching type, source and data") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["temperature"]?.jsonPrimitive?.double == 42.5
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Until Tests (Accumulation Mode)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen any with until expression accumulates events until condition is true",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,
                TestMocks.reading3ThresholdEvent
            ),
            yaml = $$"""
                do:
                  - collectReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until: . | any(.value > 100)
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "until", "expression"),
            validate = expectOutputMatching("array of 3 readings including threshold event") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                // Should collect all 3 events (the last one triggers the until condition)
                arr.size == 3 &&
                    arr[0].jsonObject["readingId"]?.jsonPrimitive?.content == "1" &&
                    arr[1].jsonObject["readingId"]?.jsonPrimitive?.content == "2" &&
                    arr[2].jsonObject["readingId"]?.jsonPrimitive?.content == "3"
            }
        ),

        WorkflowTestCase(
            name = "listen any with until expression can use length condition",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,
                TestMocks.reading3ThresholdEvent
            ),
            yaml = $$"""
                do:
                  - collectReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until: length >= 2
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "until", "expression"),
            validate = expectOutputMatching("array of exactly 2 readings") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                // Should stop after 2 events (length >= 2)
                arr.size == 2 &&
                    arr[0].jsonObject["readingId"]?.jsonPrimitive?.content == "1" &&
                    arr[1].jsonObject["readingId"]?.jsonPrimitive?.content == "2"
            }
        ),

        WorkflowTestCase(
            name = "listen any with until event accumulates until termination event",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,
                TestMocks.stopMonitoringEvent
            ),
            yaml = """
                do:
                  - monitorReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until:
                            one:
                              with:
                                type: monitoring.stopped
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "until", "event"),
            validate = expectOutputMatching("array of 2 readings (excluding termination event)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                // Should collect 2 sensor readings, not the monitoring.stopped event
                arr.size == 2 &&
                    arr[0].jsonObject["readingId"]?.jsonPrimitive?.content == "1" &&
                    arr[1].jsonObject["readingId"]?.jsonPrimitive?.content == "2"
            }
        ),

        WorkflowTestCase(
            name = "listen any with until event returns empty array if termination event arrives first",
            cloudEvents = listOf(
                TestMocks.stopMonitoringEvent
            ),
            yaml = """
                do:
                  - monitorReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until:
                            one:
                              with:
                                type: monitoring.stopped
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "until", "event"),
            validate = expectOutputMatching("empty array (termination arrived first)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isEmpty()
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Any Strategy with Multiple Filters
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen any strategy matches first event from multiple filters",
            cloudEvents = listOf(TestMocks.highTemperatureEvent),
            yaml = $$"""
                do:
                  - waitForAnyAlert:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                                data: '${ .temperature > 40 }'
                            - with:
                                type: alert.triggered
                                data: '${ .severity == "critical" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "any", "multiple-filters"),
            validate = expectOutputMatching("first matching event (temperature)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.size == 1 &&
                    arr[0].jsonObject["temperature"]?.jsonPrimitive?.double == 42.5
            }
        ),

        // NOTE: Test for "any: []" (empty filter array) is removed because the SDK
        // interprets it as ALL strategy with 0 filters, which immediately returns
        // empty results instead of matching any event. This is undefined behavior
        // per the Serverless Workflow spec.

        // ─────────────────────────────────────────────────────────────────────────
        // All Strategy (wait for all specified events)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen all strategy waits for all event types",
            cloudEvents = listOf(
                TestMocks.orderCreatedCloudEvent,
                TestMocks.paymentCompletedCloudEvent
            ),
            yaml = """
                do:
                  - waitForOrderAndPayment:
                      listen:
                        to:
                          all:
                            - with:
                                type: order.created
                            - with:
                                type: payment.completed
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "all"),
            validate = expectOutputMatching("both order and payment events") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.size == 2
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Read Mode (envelope vs data)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen with read envelope includes full CloudEvent structure",
            cloudEvents = listOf(TestMocks.eventWithSubject),
            yaml = """
                do:
                  - waitForShipment:
                      listen:
                        to:
                          one:
                            with:
                              type: order.shipped
                        read: envelope
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "read", "envelope"),
            validate = expectOutputMatching("full CloudEvent envelope") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    arr[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull == "order.shipped" &&
                    arr[0].jsonObject["subject"]?.jsonPrimitive?.contentOrNull == "order/ORD-999" &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("orderId")?.jsonPrimitive?.contentOrNull == "ORD-999"
            }
        ),

        WorkflowTestCase(
            name = "listen with read data returns only payload",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
            yaml = """
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                        read: data
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "read", "data"),
            validate = expectOutputMatching("only data payload") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() &&
                    // read: data returns only the data payload, not wrapped in envelope
                    arr[0].jsonObject["orderId"]?.jsonPrimitive?.contentOrNull == "ORD-12345"
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Foreach Tests - Process events through nested tasks
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen one with foreach processes event through nested tasks",
            cloudEvents = listOf(TestMocks.orderCreatedCloudEvent),
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
            tags = setOf("listen", "cloudevents", "foreach", "one"),
            validate = expectOutputMatching("array with single processed output") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.size == 1 &&
                    arr[0].jsonObject["processed"]?.jsonPrimitive?.booleanOrNull == true &&
                    arr[0].jsonObject["orderId"]?.jsonPrimitive?.content == "ORD-12345"
            }
        ),

        WorkflowTestCase(
            name = "listen any with foreach processes event through nested tasks",
            cloudEvents = listOf(TestMocks.sensorReadingCloudEvent),
            yaml = $$"""
                do:
                  - waitForReading:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                      foreach:
                        do:
                          - logReading:
                              set:
                                logged: true
                                sensorId: ${ .sensorId }
                                temperature: ${ .temperature }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "any"),
            validate = expectOutputMatching("array with single logged reading") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.size == 1 &&
                    arr[0].jsonObject["logged"]?.jsonPrimitive?.booleanOrNull == true &&
                    arr[0].jsonObject["sensorId"]?.jsonPrimitive?.content == "SENSOR-001"
            }
        ),

        WorkflowTestCase(
            name = "listen any with until expression and foreach processes all events sequentially",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,
                TestMocks.reading3ThresholdEvent
            ),
            yaml = """
                do:
                  - collectReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until: . | any(.value > 100)
                      foreach:
                        do:
                          - processReading:
                              set:
                                processed: true
                                readingId: ${'$'}{ .readingId }
                                value: ${'$'}{ .value }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "until", "expression"),
            validate = expectOutputMatching("array of 3 processed readings") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                // Should have 3 outputs from foreach processing each event
                arr.size == 3 &&
                    arr[0].jsonObject["processed"]?.jsonPrimitive?.booleanOrNull == true &&
                    arr[0].jsonObject["readingId"]?.jsonPrimitive?.intOrNull == 1 &&
                    arr[1].jsonObject["readingId"]?.jsonPrimitive?.intOrNull == 2 &&
                    arr[2].jsonObject["readingId"]?.jsonPrimitive?.intOrNull == 3
            }
        ),

        WorkflowTestCase(
            name = "listen any with until event and foreach processes accumulated events",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,
                TestMocks.stopMonitoringEvent
            ),
            yaml = $$"""
                do:
                  - monitorReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until:
                            one:
                              with:
                                type: monitoring.stopped
                      foreach:
                        do:
                          - storeReading:
                              set:
                                stored: true
                                id: ${ .readingId }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "until", "event"),
            validate = expectOutputMatching("array of 2 stored readings (termination event excluded)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                // Should have 2 outputs (readings only, not the termination event)
                arr.size == 2 &&
                    arr[0].jsonObject["stored"]?.jsonPrimitive?.booleanOrNull == true &&
                    arr[0].jsonObject["id"]?.jsonPrimitive?.intOrNull == 1 &&
                    arr[1].jsonObject["id"]?.jsonPrimitive?.intOrNull == 2
            }
        ),

        WorkflowTestCase(
            name = "listen with foreach and empty events returns empty array",
            cloudEvents = listOf(TestMocks.stopMonitoringEvent),
            yaml = """
                do:
                  - monitorReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until:
                            one:
                              with:
                                type: monitoring.stopped
                      foreach:
                        do:
                          - storeReading:
                              set:
                                stored: true
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "until", "event", "empty"),
            validate = expectOutputMatching("empty array (termination arrived first)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isEmpty()
            }
        ),

        WorkflowTestCase(
            name = "listen all with foreach processes each matched filter event",
            cloudEvents = listOf(
                TestMocks.orderCreatedCloudEvent,
                TestMocks.paymentCompletedCloudEvent
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
                                type: payment.completed
                      foreach:
                        do:
                          - logEvent:
                              set:
                                processed: true
                                eventType: ${ .type }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "all"),
            validate = expectOutputMatching("array of 2 processed events") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.size == 2 &&
                    arr[0].jsonObject["processed"]?.jsonPrimitive?.booleanOrNull == true &&
                    arr[1].jsonObject["processed"]?.jsonPrimitive?.booleanOrNull == true
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Sequential Processing Test - Verifies foreach processes events one at a time
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen foreach processes events sequentially with delay preserving order",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,
                TestMocks.reading3ThresholdEvent
            ),
            yaml = $$"""
                do:
                  - setContext:
                      set:
                        value: 0
                      export:
                        as: ${ . }
                  - collectReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until: . | any(.value > 100)
                      foreach:
                        do:
                          - captureContext:
                              set:
                                value: ${ $context.value }
                          - simulateSlowProcessing:
                              wait:
                                milliseconds: 400
                          - returnResult:
                              set:
                                value: ${ .value + 1 }
                              export:
                                as: ${ . }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "sequential"),
            validate = expectOutputMatching("events processed in order: 1, 2, 3 (proves sequential processing)") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                // If processing is sequential, outputs are in the same order as events arrived
                // If parallel, order could be scrambled (first completed = first in output)
                // With 100ms delay each, parallel processing would mix up order
                arr.size == 3 &&
                    arr[0].jsonObject["value"]?.jsonPrimitive?.intOrNull == 1 &&
                    arr[1].jsonObject["value"]?.jsonPrimitive?.intOrNull == 2 &&
                    arr[2].jsonObject["value"]?.jsonPrimitive?.intOrNull == 3
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Error Handling Test - Verifies errors in foreach propagate correctly
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen foreach error is caught by inner try-catch and returns error info",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,  // Second event will trigger failure but be caught
                TestMocks.reading3ThresholdEvent  // Third event triggers until condition
            ),
            yaml = $$"""
                do:
                  - collectReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until: . | any(.value > 100)
                      foreach:
                        do:
                          - handleEvent:
                              try:
                                - checkValue:
                                    if: ${ .readingId == 2 }
                                    raise:
                                      error:
                                        type: https://serverlessworkflow.io/errors/processing
                                        status: 400
                                        title: Processing failed
                                        detail: Failed to process reading with id 2
                                - processEvent:
                                    set:
                                      processed: true
                                      readingId: ${ .readingId }
                              catch:
                                as: caughtError
                                do:
                                  - returnError:
                                      set:
                                        caught: true
                                        errorType: ${ $caughtError.type }
                                        errorStatus: ${ $caughtError.status }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "error", "try-catch"),
            validate = expectOutputMatching("3 iterations: 2 processed, 1 caught error") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.size == 3 &&
                    // First event processed successfully
                    arr[0].jsonObject["processed"]?.jsonPrimitive?.booleanOrNull == true &&
                    arr[0].jsonObject["readingId"]?.jsonPrimitive?.intOrNull == 1 &&
                    // Second event caught error
                    arr[1].jsonObject["caught"]?.jsonPrimitive?.booleanOrNull == true &&
                    arr[1].jsonObject["errorType"]?.jsonPrimitive?.contentOrNull?.contains("processing") == true &&
                    arr[1].jsonObject["errorStatus"]?.jsonPrimitive?.intOrNull == 400 &&
                    // Third event processed successfully
                    arr[2].jsonObject["processed"]?.jsonPrimitive?.booleanOrNull == true &&
                    arr[2].jsonObject["readingId"]?.jsonPrimitive?.intOrNull == 3
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Fail-Fast Test - Verifies foreach stops on error and doesn't process remaining events
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen foreach with outer try-catch stops on error and skips remaining events",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,  // Second event will trigger failure
                TestMocks.reading3ThresholdEvent  // Third event should NOT be processed
            ),
            yaml = $$"""
                do:
                  - setProcessedCount:
                      set:
                        processedCount: 0
                      export:
                        as: ${ . }
                  - handleReadings:
                      try:
                        - collectReadings:
                            listen:
                              to:
                                any:
                                  - with:
                                      type: sensor.reading
                                until: . | any(.value > 100)
                            foreach:
                              do:
                                - checkValue:
                                    if: ${ .readingId == 2 }
                                    raise:
                                      error:
                                        type: https://serverlessworkflow.io/errors/processing
                                        status: 400
                                        title: Processing failed
                                - incrementCount:
                                    set:
                                      processedCount: ${ $context.processedCount + 1 }
                                    export:
                                      as: ${ . }
                      catch:
                        as: caughtError
                        do:
                          - returnResult:
                              set:
                                failed: true
                                errorType: ${ $caughtError.type }
                                processedBeforeError: ${ $context.processedCount }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "error", "fail-fast"),
            validate = expectOutputMatching("only 1 event processed before error, third event skipped") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                // Verify error was caught
                obj["failed"]?.jsonPrimitive?.booleanOrNull == true &&
                    obj["errorType"]?.jsonPrimitive?.contentOrNull?.contains("processing") == true &&
                    // Only 1 event was processed (event 1), event 2 failed, event 3 was never processed
                    obj["processedBeforeError"]?.jsonPrimitive?.intOrNull == 1
            }
        )
    )
}
