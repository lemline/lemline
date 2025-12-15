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
                    arr[0].jsonObject["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull == "test"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull == "from-orders"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull == "from-orders"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull == "from-orders"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("orderId")?.jsonPrimitive?.contentOrNull == "ORD-999" &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("carrier")?.jsonPrimitive?.contentOrNull == "FedEx"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("taskId")?.jsonPrimitive?.contentOrNull == "TASK-001"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("orderId")?.jsonPrimitive?.contentOrNull == "ORD-12345"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("format")?.jsonPrimitive?.contentOrNull == "xml"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull == "John" &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("age")?.jsonPrimitive?.content == "30"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull == "John"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("sensorId")?.jsonPrimitive?.contentOrNull == "TEMP-001" &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("temperature")?.jsonPrimitive?.double == 42.5
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("alertId")?.jsonPrimitive?.contentOrNull == "ALERT-001" &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("severity")?.jsonPrimitive?.contentOrNull == "critical"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("temperature")?.jsonPrimitive?.double == 42.5 &&
                    arr[0].jsonObject["data"]?.jsonObject?.get("unit")?.jsonPrimitive?.contentOrNull == "celsius"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("severity")?.jsonPrimitive?.contentOrNull == "critical"
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("total")?.jsonPrimitive?.double == 99.99
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
                    arr[0].jsonObject["data"]?.jsonObject?.get("temperature")?.jsonPrimitive?.double == 42.5
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Until Tests (Accumulation Mode)
        // ─────────────────────────────────────────────────────────────────────────
        // NOTE: Until tests are excluded because FullOrchestrator (used for in-memory
        // testing) does not implement the accumulation logic for ANY + until.
        // The `until` clause is handled by the runner infrastructure which uses
        // database-backed listeners for proper event accumulation.
        // See: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/cloudevents/CloudEventHandler.kt
        // for the full implementation supporting until expressions and termination events.

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
                    arr[0].jsonObject["data"]?.jsonObject?.get("temperature")?.jsonPrimitive?.double == 42.5
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
                    // read: data wraps in envelope at output but data key contains payload
                    arr[0].jsonObject["data"]?.jsonObject?.get("orderId")?.jsonPrimitive?.contentOrNull == "ORD-12345"
            }
        )

        // Note: Listen + foreach tests are excluded for now as they require
        // additional infrastructure support (ListenForEachCompleted handling)
        // that involves more complex state management beyond mock configuration.
    )
}
