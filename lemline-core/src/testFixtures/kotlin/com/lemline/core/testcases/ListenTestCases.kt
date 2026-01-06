// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.cloudevents.CloudEventUtils.toJsonElement
import com.lemline.core.testcases.TestMocks.criticalAlertEvent
import com.lemline.core.testcases.TestMocks.highTemperatureEvent
import com.lemline.core.testcases.TestMocks.orderCreatedEvent
import com.lemline.core.testcases.TestMocks.paymentCompletedEvent
import com.lemline.core.testcases.TestMocks.sensorReadingEvent
import com.lemline.core.testcases.TestMocks.userRegisteredEvent
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
                output == JsonArray(listOf(orderCreatedEvent))
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
                output == JsonArray(listOf(userRegisteredEvent))
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
                output == JsonArray(listOf(TestMocks.genericEvent))
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
                output == buildJsonObject {
                    put("id", "ORD-12345")
                    put("customer", "CUST-001")
                }
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
                output == buildJsonObject {
                    put("productId", "PROD-001")
                    put("quantity", 2)
                    put("price", 49.99)
                }
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
                output == buildJsonObject {
                    put("orderId", "ORD-12345")
                    put("processed", true)
                }
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
                output == TestMocks.post1Response
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
                output == JsonArray(listOf(sensorReadingEvent))
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
            // SKIP: Uses range check for tempF (74.3 calculated)
            validate = expectOutputMatching("sensor data with Fahrenheit conversion") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["sensor"]?.jsonPrimitive?.contentOrNull == "SENSOR-001" &&
                    obj["tempF"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { it > 74 && it < 75 } == true
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
                output == buildJsonObject {
                    put("highValueOrder", true)
                    put("total", 99.99)
                }
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
                output == JsonArray(listOf(buildJsonObject { put("message", "test") }))
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
                output == JsonArray(listOf(buildJsonObject { put("message", "from-orders") }))
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
                output == JsonArray(listOf(buildJsonObject { put("message", "from-orders") }))
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
                output == JsonArray(listOf(buildJsonObject { put("message", "from-orders") }))
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
                output == JsonArray(listOf(buildJsonObject {
                    put("orderId", "ORD-999")
                    put("carrier", "FedEx")
                }))
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
                output == JsonArray(listOf(buildJsonObject { put("taskId", "TASK-001") }))
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
                output == JsonArray(listOf(orderCreatedEvent))
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
                output == JsonArray(listOf(buildJsonObject { put("format", "xml") }))
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
                output == JsonArray(listOf(buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }))
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
                output == JsonArray(listOf(buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }))
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by Data (expression evaluated against event payload)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by data expression (temperature threshold)",
            cloudEvents = listOf(highTemperatureEvent),
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
                output == JsonArray(listOf(highTemperatureEvent.toJsonElement(ListenAndReadAs.DATA)))
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression (string equality)",
            cloudEvents = listOf(criticalAlertEvent),
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
                output == JsonArray(listOf(criticalAlertEvent.toJsonElement(ListenAndReadAs.DATA)))
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression (complex condition with and)",
            cloudEvents = listOf(highTemperatureEvent),
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
                output == JsonArray(listOf(highTemperatureEvent.toJsonElement(ListenAndReadAs.DATA)))
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression (or condition)",
            cloudEvents = listOf(criticalAlertEvent),
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
                output == JsonArray(listOf(criticalAlertEvent.toJsonElement(ListenAndReadAs.DATA)))
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
                output == JsonArray(listOf(orderCreatedEvent))
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
                output == JsonArray(listOf(buildJsonObject { put("message", "from-orders") }))
            }
        ),

        WorkflowTestCase(
            name = "listen can combine type, source and data filters",
            cloudEvents = listOf(highTemperatureEvent),
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
                output == JsonArray(listOf(highTemperatureEvent.toJsonElement(ListenAndReadAs.DATA)))
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
                output == JsonArray(
                    listOf(
                        buildJsonObject { put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("readingId", 2); put("value", 25) },
                        buildJsonObject { put("readingId", 3); put("value", 150) }
                    ))
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
                output == JsonArray(
                    listOf(
                        buildJsonObject { put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("readingId", 2); put("value", 25) }
                    ))
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
                output == JsonArray(
                    listOf(
                        buildJsonObject { put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("readingId", 2); put("value", 25) }
                    ))
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
                output == JsonArray(emptyList())
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Any Strategy with Multiple Filters
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen any strategy matches first event from multiple filters",
            cloudEvents = listOf(highTemperatureEvent),
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
                output == JsonArray(listOf(highTemperatureEvent.toJsonElement(ListenAndReadAs.DATA)))
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
                output == JsonArray(listOf(orderCreatedEvent, paymentCompletedEvent))
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
            // SKIP: CloudEvent envelope contains dynamic 'id' field (random UUID)
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
                output == JsonArray(listOf(orderCreatedEvent))
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
                output == JsonArray(listOf(buildJsonObject {
                    put("processed", true)
                    put("orderId", "ORD-12345")
                }))
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
                output == JsonArray(listOf(buildJsonObject {
                    put("logged", true)
                    put("sensorId", "SENSOR-001")
                    put("temperature", 23.5)
                }))
            }
        ),

        WorkflowTestCase(
            name = "listen any with until expression and foreach processes all events sequentially",
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
                      foreach:
                        do:
                          - processReading:
                              set:
                                processed: true
                                readingId: ${ .readingId }
                                value: ${ .value }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "until", "expression"),
            validate = expectOutputMatching("array of 3 processed readings") { output ->
                output == JsonArray(
                    listOf(
                        buildJsonObject { put("processed", true); put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("processed", true); put("readingId", 2); put("value", 25) },
                        buildJsonObject { put("processed", true); put("readingId", 3); put("value", 150) }
                    ))
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
                output == JsonArray(
                    listOf(
                        buildJsonObject { put("stored", true); put("id", 1) },
                        buildJsonObject { put("stored", true); put("id", 2) }
                    ))
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
                output == JsonArray(emptyList())
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
                // Note: .type is null for data-only events (no envelope)
                output == JsonArray(
                    listOf(
                        buildJsonObject { put("processed", true); put("eventType", JsonNull) },
                        buildJsonObject { put("processed", true); put("eventType", JsonNull) }
                    ))
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
                output == JsonArray(
                    listOf(
                        buildJsonObject { put("value", 1) },
                        buildJsonObject { put("value", 2) },
                        buildJsonObject { put("value", 3) }
                    ))
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
            // SKIP: Uses .contains() for errorType validation
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
            // SKIP: Uses .contains() for errorType validation
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
