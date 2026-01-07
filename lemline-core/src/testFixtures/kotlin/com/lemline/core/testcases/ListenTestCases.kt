// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.cloudevents.CloudEventUtils.toJsonElement
import com.lemline.core.testcases.impl.TestMocks.buildCloudEvent
import com.lemline.core.testcases.impl.TestMocks.criticalAlertEvent
import com.lemline.core.testcases.impl.TestMocks.eventWithDataSchema
import com.lemline.core.testcases.impl.TestMocks.eventWithSpecificId
import com.lemline.core.testcases.impl.TestMocks.eventWithSpecificSource
import com.lemline.core.testcases.impl.TestMocks.eventWithSubject
import com.lemline.core.testcases.impl.TestMocks.eventWithTime
import com.lemline.core.testcases.impl.TestMocks.eventWithXmlContentType
import com.lemline.core.testcases.impl.TestMocks.highTemperatureData
import com.lemline.core.testcases.impl.TestMocks.highTemperatureEvent
import com.lemline.core.testcases.impl.TestMocks.lowTemperatureData
import com.lemline.core.testcases.impl.TestMocks.lowTemperatureEvent
import com.lemline.core.testcases.impl.TestMocks.orderCreatedCloudEvent
import com.lemline.core.testcases.impl.TestMocks.orderCreatedCloudEvent2
import com.lemline.core.testcases.impl.TestMocks.orderCreatedData
import com.lemline.core.testcases.impl.TestMocks.orderCreatedData2
import com.lemline.core.testcases.impl.TestMocks.paymentCompletedCloudEvent
import com.lemline.core.testcases.impl.TestMocks.paymentCompletedData
import com.lemline.core.testcases.impl.TestMocks.reading1Event
import com.lemline.core.testcases.impl.TestMocks.reading2Event
import com.lemline.core.testcases.impl.TestMocks.reading3ThresholdEvent
import com.lemline.core.testcases.impl.TestMocks.sensorReadingCloudEvent
import com.lemline.core.testcases.impl.TestMocks.stopMonitoringEvent
import com.lemline.core.testcases.impl.TestMocks.userRegisteredCloudEvent
import com.lemline.core.testcases.impl.TestMocks.userRegisteredData
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
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
            validate = expectOutput(JsonArray(listOf(orderCreatedData)))
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
            validate = expectOutput(
                buildJsonObject {
                    put("id", "ORD-12345")
                    put("customer", "CUST-001")
                }
            )
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
            validate = expectOutput(
                buildJsonObject {
                    put("orderId", "ORD-12345")
                    put("processed", true)
                }
            )
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
            validate = expectOutput(JsonArray(listOf(userRegisteredData)))
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
            tags = setOf("listen", "filter", "id"),
            validate = expectOutput(JsonArray(listOf(buildJsonObject { put("message", "test") })))
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
            tags = setOf("listen", "filter", "source"),
            validate = expectOutput(JsonArray(listOf(buildJsonObject { put("message", "from-orders") })))
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
            tags = setOf("listen", "filter", "source", "expression"),
            validate = expectOutput(JsonArray(listOf(buildJsonObject { put("message", "from-orders") })))
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
            tags = setOf("listen", "filter", "subject"),
            validate = expectOutput(
                JsonArray(listOf(buildJsonObject {
                    put("orderId", "ORD-999")
                    put("carrier", "FedEx")
                }))
            )
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
            tags = setOf("listen", "filter", "time"),
            validate = expectOutput(JsonArray(listOf(buildJsonObject { put("taskId", "TASK-001") })))
        ),

        WorkflowTestCase(
            name = "listen can filter by time (expression)",
            cloudEvents = listOf(orderCreatedCloudEvent, eventWithTime),
            yaml = $$"""
                do:
                  - waitForRecentEvent:
                      listen:
                        to:
                          one:
                            with:
                              time: ${ . > "2024-01-01T00:00:00Z" }
            """.trimIndent(),
            tags = setOf("listen", "filter", "time", "expression"),
            // NOTE: Time expression tests (e.g., time: '${ contains("2024") }') are not supported
            // because the Serverless Workflow SDK uses JSON Schema Draft 2020-12 where format
            // validation is annotation-only by default. This causes oneOf validation to fail
            // with "2 are valid" because format:date-time doesn't actually reject runtime
            // expressions.
            validateDefinition = false,
            validate = expectOutput(JsonArray(listOf(buildJsonObject { put("taskId", "TASK-001") })))
        ),


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
            tags = setOf("listen", "filter", "datacontenttype"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData)))
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
            tags = setOf("listen", "filter", "datacontenttype"),
            validate = expectOutput(JsonArray(listOf(buildJsonObject { put("format", "xml") })))
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
            tags = setOf("listen", "filter", "dataschema"),
            validate = expectOutput(
                JsonArray(listOf(buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }))
            )
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
            tags = setOf("listen", "filter", "dataschema", "expression"),
            validate = expectOutput(
                JsonArray(listOf(buildJsonObject {
                    put("name", "John")
                    put("age", 30)
                }))
            )
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
            tags = setOf("listen", "filter", "data", "expression"),
            validate = expectOutput(JsonArray(listOf(criticalAlertEvent.toJsonElement(ListenAndReadAs.DATA))))
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
            tags = setOf("listen", "filter", "data", "expression"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData)))
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
            tags = setOf("listen", "filter", "combined"),
            validate = expectOutput(JsonArray(listOf(lowTemperatureEvent.toJsonElement(ListenAndReadAs.DATA))))
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
            tags = setOf("listen", "filter", "combined"),
            validate = expectOutput(JsonArray(listOf(highTemperatureEvent.toJsonElement(ListenAndReadAs.DATA))))
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
            tags = setOf("listen", "filter", "combined"),
            validate = expectOutput(JsonArray(listOf(buildJsonObject { put("message", "from-orders") })))
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
            tags = setOf("listen", "filter", "combined"),
            validate = expectOutput(JsonArray(listOf(highTemperatureEvent.toJsonElement(ListenAndReadAs.DATA))))
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
            tags = setOf("listen", "until", "expression"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("readingId", 2); put("value", 25) },
                        buildJsonObject { put("readingId", 3); put("value", 150) }
                    ))
            )
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
            tags = setOf("listen", "until", "expression"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("readingId", 2); put("value", 25) }
                    ))
            )
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
            tags = setOf("listen", "until", "event"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("readingId", 2); put("value", 25) }
                    ))
            )
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
            tags = setOf("listen", "until", "event"),
            validate = expectOutput(JsonArray(emptyList()))
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
            tags = setOf("listen", "any", "multiple-filters"),
            validate = expectOutput(JsonArray(listOf(highTemperatureEvent.toJsonElement(ListenAndReadAs.DATA))))
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
            tags = setOf("listen", "any", "multiple-filters"),
            validate = expectOutput(JsonArray(listOf(criticalAlertEvent.toJsonElement(ListenAndReadAs.DATA))))
        ),

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
            tags = setOf("listen", "any", "multiple-filters"),
            validate = expectOutput(JsonArray(listOf(orderCreatedCloudEvent.toJsonElement(ListenAndReadAs.DATA)))),
            // NOTE: Test for "any: []" (empty filter array) has validateDefinition = false because the
            // validation has an issue with this syntax. The test still verifies runtime behavior.
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
            tags = setOf("listen", "all"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData, highTemperatureData)))
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
            tags = setOf("listen", "all"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData, highTemperatureData)))
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
            tags = setOf("listen", "all"),
            validate = expectOutput(JsonArray(listOf(highTemperatureData, orderCreatedData)))
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
            tags = setOf("listen", "all"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData, paymentCompletedData)))
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
            tags = setOf("listen", "all"),
            validate = expectOutput(JsonArray(listOf(lowTemperatureData, orderCreatedData, paymentCompletedData)))
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
            tags = setOf("listen", "read", "envelope"),
            validate = expectOutput(JsonArray(listOf(eventWithSubject.toJsonElement(ListenAndReadAs.ENVELOPE))))
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
            tags = setOf("listen", "read", "data"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData)))
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
            tags = setOf("listen", "foreach", "one"),
            validate = expectOutput(
                JsonArray(listOf(buildJsonObject {
                    put("processed", true)
                    put("orderId", "ORD-12345")
                }))
            )
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
            tags = setOf("listen", "foreach", "any"),
            validate = expectOutput(
                JsonArray(listOf(buildJsonObject {
                    put("logged", true)
                    put("sensorId", "SENSOR-001")
                    put("temperature", 23.5)
                }))
            )
        ),

        WorkflowTestCase(
            name = "listen any with until expression and foreach processes all events sequentially",
            cloudEvents = listOf(
                reading1Event,
                reading2Event,
                reading3ThresholdEvent,
                reading1Event,
                reading2Event,
                reading3ThresholdEvent,
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
            tags = setOf("listen", "foreach", "until", "expression"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("processed", true); put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("processed", true); put("readingId", 2); put("value", 25) },
                        buildJsonObject { put("processed", true); put("readingId", 3); put("value", 150) }
                    ))
            )
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
                          - processReading:
                              set:
                                processed: true
                                readingId: ${ .readingId }
                                value: ${ .value }
            """.trimIndent(),
            tags = setOf("listen", "foreach", "until", "event"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("processed", true); put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("processed", true); put("readingId", 2); put("value", 25) },
                    )
                )
            )
        ),

        WorkflowTestCase(
            name = "listen with foreach and empty events returns empty array",
            cloudEvents = listOf(orderCreatedCloudEvent, stopMonitoringEvent),
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
            tags = setOf("listen", "foreach", "until", "event", "empty"),
            validate = expectOutput(JsonArray(emptyList()))
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
                        read: envelope
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
            tags = setOf("listen", "foreach", "all"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("processed", true); put("eventType", "order.created") },
                        buildJsonObject { put("processed", true); put("eventType", "payment.completed") }
                    ))
            )
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
            tags = setOf("listen", "foreach", "sequential"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("value", 1) },
                        buildJsonObject { put("value", 2) },
                        buildJsonObject { put("value", 3) }
                    ))
            )
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
            tags = setOf("listen", "foreach", "error", "try-catch"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("processed", true); put("readingId", 1) },
                        buildJsonObject {
                            put("caught", true)
                            put("errorType", "https://serverlessworkflow.io/errors/processing")
                            put("errorStatus", 400)
                        },
                        buildJsonObject { put("processed", true); put("readingId", 3) }
                    ))
            )
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Fail-Fast Test - Verifies foreach stops on error and doesn't process remaining events
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen any until expr, with foreach, outer try-catch stops on error and skips remaining events",
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
            tags = setOf("listen", "foreach", "error", "fail-fast"),
            validate = expectOutput(
                buildJsonObject {
                    put("failed", true)
                    put("errorType", "https://serverlessworkflow.io/errors/processing")
                    put("processedBeforeError", 1)
                }
            )
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Read Mode: Raw (implementation-specific)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen one, with read raw returns raw event format",
            cloudEvents = listOf(orderCreatedCloudEvent),
            yaml = """
                do:
                  - waitForOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                        read: raw
            """.trimIndent(),
            tags = setOf("listen", "read", "raw"),
            // NOTE: 'raw' mode is implementation-specific. This test documents the expected behavior.
            // The actual output format depends on the runtime implementation.
            validate = expectOutput(
                JsonArray(
                    listOf(orderCreatedCloudEvent.toJsonElement(ListenAndReadAs.RAW))
                )
            )
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Foreach with Custom Item Variable Name
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen one, with foreach + custom item variable name",
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
                        item: myEvent
                        do:
                          - processOrder:
                              set:
                                processed: true
                                orderId: ${ $myEvent.orderId }
                                customerId: ${ $myEvent.customerId }
            """.trimIndent(),
            tags = setOf("listen", "foreach", "item"),
            validate = expectOutput(
                JsonArray(listOf(buildJsonObject {
                    put("processed", true)
                    put("orderId", "ORD-12345")
                    put("customerId", "CUST-001")
                }))
            )
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Foreach with Index Variable (at)
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen any until expr, with foreach + at and item variables",
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
                          until: any(.value > 100)
                      foreach:
                        item: reading
                        at: counter
                        do:
                          - processReading:
                              set:
                                index: ${ $counter
                                readingId: ${ $reading.readingId }
                                value: ${ $reading.value }
            """.trimIndent(),
            tags = setOf("listen", "foreach", "at", "index"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("index", 0); put("readingId", 1); put("value", 10) },
                        buildJsonObject { put("index", 1); put("readingId", 2); put("value", 25) },
                        buildJsonObject { put("index", 2); put("readingId", 3); put("value", 150) }
                    ))
            )
        ),

        WorkflowTestCase(
            name = "listen foreach with both item and at variables in all strategy",
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
                        item: evt
                        at: idx
                        do:
                          - logEvent:
                              set:
                                position: ${ $idx }
                                orderId: ${ $evt.orderId }
            """.trimIndent(),
            tags = setOf("listen", "foreach", "item", "at", "all"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("position", 0); put("orderId", "ORD-12345") },
                        buildJsonObject { put("position", 1); put("orderId", "ORD-12345") }
                    ))
            )
        ),

        // ─────────────────────────────────────────────────────────────────────────
        // Event Correlation Tests
        // ─────────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "listen with correlate filters events by workflow context",
            cloudEvents = listOf(orderCreatedCloudEvent, orderCreatedCloudEvent2),
            yaml = $$"""
                do:
                  - waitForMyOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                            correlate:
                              orderId:
                                from: ${ .orderId }
                                expect: ORD-54321
            """.trimIndent(),
            tags = setOf("listen", "correlate"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData2)))
        ),

        WorkflowTestCase(
            name = "listen with multiple correlate keys requires all to match",
            cloudEvents = listOf(orderCreatedCloudEvent, orderCreatedCloudEvent2),
            yaml = $$"""
                do:
                  - waitForMyOrder:
                      listen:
                        to:
                          one:
                            with:
                              type: order.created
                            correlate:
                              orderId:
                                from: ${ .orderId }
                                expect: ORD-54321
                              customerId:
                                from: ${ .customerId }
                                expect: CUST-002
            """.trimIndent(),
            tags = setOf("listen", "correlate"),
            validate = expectOutput(JsonArray(listOf(orderCreatedData2)))
        ),

        WorkflowTestCase(
            name = "listen with correlate auto-correlation (no expect)",
            cloudEvents = listOf(
                // First order.created event establishes the correlation value
                buildCloudEvent(
                    type = "order.created",
                    data = buildJsonObject {
                        put("orderId", "ORD-AUTO-123")
                        put("customerId", "CUST-001")
                    }
                ),
                // Different order.paid event - should be ignored
                buildCloudEvent(
                    type = "order.paid",
                    data = buildJsonObject {
                        put("orderId", "ORD-DIFFERENT")
                        put("amount", 50.0)
                    }
                ),
                // Matching order.paid event - should be received
                buildCloudEvent(
                    type = "order.paid",
                    data = buildJsonObject {
                        put("orderId", "ORD-AUTO-123")
                        put("amount", 99.99)
                    }
                )
            ),
            yaml = $$"""
                do:
                  - waitForOrderAndPayment:
                      listen:
                        to:
                          all:
                            - with:
                                type: order.created
                              correlate:
                                orderId:
                                  from: '${ .orderId }'
                            - with:
                                type: order.paid
                              correlate:
                                orderId:
                                  from: '${ .orderId }'
            """.trimIndent(),
            tags = setOf("listen", "correlate", "auto-correlation"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("orderId", "ORD-AUTO-123")
                            put("customerId", "CUST-001")
                        },
                        buildJsonObject {
                            put("orderId", "ORD-AUTO-123")
                            put("amount", 99.99)
                        }
                    )
                )
            )
        )
    )
}
