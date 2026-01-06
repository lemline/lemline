// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.cloudevents.CloudEventUtils.toJsonElement
import com.lemline.core.testcases.TestMocks.criticalAlertEvent
import com.lemline.core.testcases.TestMocks.eventWithDataSchema
import com.lemline.core.testcases.TestMocks.eventWithSpecificId
import com.lemline.core.testcases.TestMocks.eventWithSpecificSource
import com.lemline.core.testcases.TestMocks.eventWithSubject
import com.lemline.core.testcases.TestMocks.eventWithTime
import com.lemline.core.testcases.TestMocks.eventWithXmlContentType
import com.lemline.core.testcases.TestMocks.highTemperatureData
import com.lemline.core.testcases.TestMocks.highTemperatureEvent
import com.lemline.core.testcases.TestMocks.lowTemperatureData
import com.lemline.core.testcases.TestMocks.lowTemperatureEvent
import com.lemline.core.testcases.TestMocks.orderCreatedCloudEvent
import com.lemline.core.testcases.TestMocks.orderCreatedData
import com.lemline.core.testcases.TestMocks.paymentCompletedCloudEvent
import com.lemline.core.testcases.TestMocks.paymentCompletedData
import com.lemline.core.testcases.TestMocks.reading1Event
import com.lemline.core.testcases.TestMocks.reading2Event
import com.lemline.core.testcases.TestMocks.reading3ThresholdEvent
import com.lemline.core.testcases.TestMocks.sensorReadingCloudEvent
import com.lemline.core.testcases.TestMocks.stopMonitoringEvent
import com.lemline.core.testcases.TestMocks.userRegisteredCloudEvent
import com.lemline.core.testcases.TestMocks.userRegisteredData
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
            cloudEvents = listOf(orderCreatedCloudEvent),
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
                output == JsonArray(listOf(orderCreatedData))
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Listen with Output Transformation
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen result can be transformed with output as",
            cloudEvents = listOf(orderCreatedCloudEvent),
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

        // ─────────────────────────────────────────────────────────────────────────
        // Listen in Workflow Steps
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can be chained with other tasks",
            cloudEvents = listOf(orderCreatedCloudEvent),
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

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by Type
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by event type",
            cloudEvents = listOf(orderCreatedCloudEvent, userRegisteredCloudEvent),
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
                output == JsonArray(listOf(userRegisteredData))
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by ID
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by event id (exact match)",
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithSpecificId),
            yaml = """
                do:
                  - waitForSpecificEvent:
                      listen:
                        to:
                          one:
                            with:
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
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithSpecificSource),
            yaml = """
                do:
                  - waitForOrdersEvent:
                      listen:
                        to:
                          one:
                            with:
                              source: https://orders.example.com/api
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "source"),
            validate = expectOutputMatching("event from orders source") { output ->
                output == JsonArray(listOf(buildJsonObject { put("message", "from-orders") }))
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by source with expression (startswith via slice)",
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithSpecificSource),
            yaml = $$"""
                do:
                  - waitForOrdersEvent:
                      listen:
                        to:
                          one:
                            with:
                              source: '${ .[0:14] == "https://orders" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "source", "expression"),
            validate = expectOutputMatching("event from orders source (startswith)") { output ->
                output == JsonArray(listOf(buildJsonObject { put("message", "from-orders") }))
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Filter by Subject (exact string match)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can filter by subject (exact match)",
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithSubject),
            yaml = """
                do:
                  - waitForOrderShipped:
                      listen:
                        to:
                          one:
                            with:
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
            cloudEvents = listOf(orderCreatedCloudEvent, orderCreatedCloudEvent),
            yaml = """
                do:
                  - waitForJsonEvent:
                      listen:
                        to:
                          one:
                            with:
                              datacontenttype: application/json
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "datacontenttype"),
            validate = expectOutputMatching("event with json content type") { output ->
                output == JsonArray(listOf(orderCreatedData))
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by datacontenttype (application/xml)",
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithXmlContentType),
            yaml = """
                do:
                  - waitForXmlEvent:
                      listen:
                        to:
                          one:
                            with:
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
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithDataSchema),
            yaml = """
                do:
                  - waitForValidatedEvent:
                      listen:
                        to:
                          one:
                            with:
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
            name = "listen can filter by dataschema with expression",
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithDataSchema),
            yaml = $$"""
                do:
                  - waitForPersonSchema:
                      listen:
                        to:
                          one:
                            with:
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
            name = "listen can filter by data expression (or condition)",
            cloudEvents = listOf(orderCreatedCloudEvent, criticalAlertEvent),
            yaml = $$"""
                do:
                  - waitForImportantAlert:
                      listen:
                        to:
                          one:
                            with:
                              data: '${ .severity == "critical" or .severity == "high" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "data", "expression"),
            validate = expectOutputMatching("important alert (critical or high)") { output ->
                output == JsonArray(listOf(criticalAlertEvent.toJsonElement(ListenAndReadAs.DATA)))
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression (nested field access)",
            cloudEvents = listOf(criticalAlertEvent, orderCreatedCloudEvent),
            yaml = $$"""
                do:
                  - waitForLargeOrder:
                      listen:
                        to:
                          one:
                            with:
                              data: '${ .total > 50 and .currency == "USD" }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "data", "expression"),
            validate = expectOutputMatching("large USD order") { output ->
                output == JsonArray(listOf(orderCreatedData))
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression 1 ",
            cloudEvents = listOf(highTemperatureEvent, lowTemperatureEvent, highTemperatureEvent),
            yaml = $$"""
                do:
                  - waitForCriticalReading:
                      listen:
                        to:
                          one:
                            with:
                              type: sensor.reading
                              source: '${ .[0:12] == "https://test" }'
                              data: '${ .temperature > 10 and .temperature < 30 }'
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "filter", "combined"),
            validate = expectOutputMatching("event matching type, source and data") { output ->
                output == JsonArray(listOf(lowTemperatureEvent.toJsonElement(ListenAndReadAs.DATA)))
            }
        ),

        WorkflowTestCase(
            name = "listen can filter by data expression 2",
            cloudEvents = listOf(lowTemperatureEvent, highTemperatureEvent),
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
        // Combined Filters (multiple attributes)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen can combine type and source filters",
            cloudEvents = listOf(criticalAlertEvent, orderCreatedCloudEvent, eventWithSpecificSource),
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
            cloudEvents = listOf(criticalAlertEvent, lowTemperatureEvent, highTemperatureEvent),
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
            cloudEvents = listOf(reading1Event, reading2Event, reading3ThresholdEvent),
            yaml = """
                do:
                  - collectReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until: any(.value > 100)
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
                reading1Event,
                orderCreatedCloudEvent,
                reading2Event,
                orderCreatedCloudEvent,
                reading3ThresholdEvent
            ),
            yaml = """
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
                reading1Event,
                orderCreatedCloudEvent,
                reading2Event,
                orderCreatedCloudEvent,
                stopMonitoringEvent
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
                orderCreatedCloudEvent,
                stopMonitoringEvent
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
            name = "listen any strategy matches first event from multiple filters 1",
            cloudEvents = listOf(orderCreatedCloudEvent, highTemperatureEvent),
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

        WorkflowTestCase(
            name = "listen any strategy matches first event from multiple filters 2",
            cloudEvents = listOf(orderCreatedCloudEvent, criticalAlertEvent),
            yaml = $$"""
                do:
                  - waitForAnyAlert:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                                data: ${ .temperature > 40 }
                            - with:
                                type: alert.triggered
                                data: ${ .severity == "critical" }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "any", "multiple-filters"),
            validate = expectOutputMatching("first matching event (temperature)") { output ->
                output == JsonArray(listOf(criticalAlertEvent.toJsonElement(ListenAndReadAs.DATA)))
            }
        ),

        // NOTE: Test for "any: []" (empty filter array) has validateDefinition = false because the
        // validation has an issue with this syntax. The test still verifies runtime behavior.

        WorkflowTestCase(
            name = "listen any[] strategy matches any event",
            cloudEvents = listOf(orderCreatedCloudEvent, criticalAlertEvent),
            yaml = """
                do:
                  - waitForAnyAlert:
                      listen:
                        to:
                          any: []
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "any", "multiple-filters"),
            validate = expectOutputMatching("first matching event (temperature)") { output ->
                output == JsonArray(listOf(orderCreatedCloudEvent.toJsonElement(ListenAndReadAs.DATA)))
            },
            validateDefinition = false
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // All Strategy (wait for all specified events)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen all strategy waits for all event types, received in order",
            cloudEvents = listOf(
                orderCreatedCloudEvent,
                lowTemperatureEvent,
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
                                data:  ${ .temperature > 30 }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "all"),
            validate = expectOutputMatching("both order and payment events") { output ->
                output == JsonArray(listOf(orderCreatedData, highTemperatureData))
            }
        ),

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
            tags = setOf("listen", "cloudevents", "all"),
            validate = expectOutputMatching("both order and payment events") { output ->
                output == JsonArray(listOf(orderCreatedData, highTemperatureData))
            }
        ),

        WorkflowTestCase(
            name = "listen all strategy waits for all event types, output preserves the time order of filters",
            cloudEvents = listOf(
                highTemperatureEvent,
                orderCreatedCloudEvent,
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
                                data:  ${ .temperature > 30 }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "all"),
            validate = expectOutputMatching("both order and payment events") { output ->
                output == JsonArray(listOf(highTemperatureData, orderCreatedData))
            }
        ),

        WorkflowTestCase(
            name = "listen all strategy waits for all event types",
            cloudEvents = listOf(
                lowTemperatureEvent,
                orderCreatedCloudEvent,
                highTemperatureEvent,
                paymentCompletedCloudEvent
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
                output == JsonArray(listOf(orderCreatedData, paymentCompletedData))
            }
        ),

        WorkflowTestCase(
            name = "listen all strategy waits for all event types, taking the first of each",
            cloudEvents = listOf(
                lowTemperatureEvent,
                orderCreatedCloudEvent,
                highTemperatureEvent,
                paymentCompletedCloudEvent
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
                            - with:
                                data:  ${ .temperature > 10 }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "all"),
            validate = expectOutputMatching("both order and payment events") { output ->
                output == JsonArray(listOf(lowTemperatureData, orderCreatedData, paymentCompletedData))
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Read Mode (envelope vs data)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen with read envelope includes full CloudEvent structure",
            cloudEvents = listOf(eventWithSubject),
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
                output == JsonArray(listOf(eventWithSubject.toJsonElement(ListenAndReadAs.ENVELOPE)))
            }
        ),

        WorkflowTestCase(
            name = "listen with read data returns only payload",
            cloudEvents = listOf(orderCreatedCloudEvent),
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
                output == JsonArray(listOf(orderCreatedData))
            }
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Foreach Tests - Process events through nested tasks
        // ─────────────────────────────────────────────────────────────────────────

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
            cloudEvents = listOf(sensorReadingCloudEvent),
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
                reading1Event,
                reading2Event,
                reading3ThresholdEvent
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
                reading1Event,
                reading2Event,
                stopMonitoringEvent
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
            cloudEvents = listOf(stopMonitoringEvent),
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
                orderCreatedCloudEvent,
                paymentCompletedCloudEvent
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
                reading1Event,
                reading2Event,
                reading3ThresholdEvent
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
                reading1Event,
                reading2Event,  // Second event will trigger failure but be caught
                reading3ThresholdEvent  // Third event triggers until condition
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
                reading1Event,
                reading2Event,  // Second event will trigger failure
                reading3ThresholdEvent  // Third event should NOT be processed
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
