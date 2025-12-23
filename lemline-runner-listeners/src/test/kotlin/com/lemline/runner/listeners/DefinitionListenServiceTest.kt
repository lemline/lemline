// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.listeners

import com.lemline.core.workflows.WorkflowCache
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.URI
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for DefinitionListenService.
 * These tests verify event matching against workflow definitions.
 */
class DefinitionListenServiceTest {

    private val service = DefinitionListenService()

    @BeforeEach
    fun setup() {
        WorkflowCache.clear()
    }

    private fun buildCloudEvent(
        type: String,
        source: String = "https://test.example.com",
        subject: String? = null,
        data: String? = null
    ): CloudEvent {
        val builder = CloudEventBuilder.v1()
            .withId("test-event-id")
            .withType(type)
            .withSource(URI.create(source))

        subject?.let { builder.withSubject(it) }
        data?.let { builder.withData("application/json", it.toByteArray()) }

        return builder.build()
    }

    private fun eventDataProvider(data: String?): () -> JsonElement = {
        data?.let { Json.parseToJsonElement(it) } ?: Json.parseToJsonElement("{}")
    }

    @Nested
    inner class FindMatchingUntilEventsTests {

        @Test
        fun `should return empty list when no workflows are cached`() {
            // Given: No workflows in cache
            val event = buildCloudEvent(type = "com.example.Terminate")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should return empty list when workflow has no until event filter`() {
            // Given: A workflow with ONE strategy (no until condition)
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should return empty list when workflow has until expression not event`() {
            // Given: A workflow with ANY + until expression (not event filter)
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until: '${'$'}{ .count >= 5 }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event against until event filter by type`() {
            // Given: A workflow with ANY + until event filter
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Terminate")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            matches[0].workflowInfo.name.toString() shouldBe "test-workflow"
        }

        @Test
        fun `should not match when event type does not match until filter`() {
            // Given: A workflow with ANY + until event filter for different type
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.OtherEvent")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should not match when source does not match until filter`() {
            // Given: A workflow with until filter matching type AND source
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
                                source: https://specific.source.com
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            // Event with matching type but different source
            val nonMatchingEvent = buildCloudEvent(
                type = "com.example.Terminate",
                source = "https://different.source.com"
            )

            // When
            val matches = service.findMatchingUntilEvents(nonMatchingEvent, eventDataProvider(null))

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event against until filter by subject`() {
            // Given: A workflow with until filter matching subject
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
                                subject: order-123
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Terminate",
                subject = "order-123"
            )

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should match multiple workflows with same until event type`() {
            // Given: Multiple workflows with same until event type
            val yaml1 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-one
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
            """.trimIndent()

            val yaml2 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-two
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.OtherEvent
                          until:
                            one:
                              with:
                                type: com.example.Terminate
            """.trimIndent()

            WorkflowCache.parseYamlAndPut(yaml1)
            WorkflowCache.parseYamlAndPut(yaml2)

            val event = buildCloudEvent(type = "com.example.Terminate")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 2
        }

        @Test
        fun `should only match workflows whose until filter matches`() {
            // Given: Multiple workflows, only one matches
            val yaml1 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-matches
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
            """.trimIndent()

            val yaml2 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-no-match
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.OtherEvent
                          until:
                            one:
                              with:
                                type: com.example.DifferentTerminate
            """.trimIndent()

            WorkflowCache.parseYamlAndPut(yaml1)
            WorkflowCache.parseYamlAndPut(yaml2)

            val event = buildCloudEvent(type = "com.example.Terminate")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            matches[0].workflowInfo.name.toString() shouldBe "workflow-matches"
        }

        @Test
        fun `should return correct workflow info in match result`() {
            // Given: A workflow with specific info
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: my-workflow
                  version: '2.0.0'
                  namespace: production
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Terminate")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            val match = matches[0]
            match.workflowInfo.namespace.toString() shouldBe "production"
            match.workflowInfo.name.toString() shouldBe "my-workflow"
            match.workflowInfo.version.toString() shouldBe "2.0.0"
        }

        @Test
        fun `should include hasForeach in match result`() {
            // Given: A workflow with foreach enabled
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
                      foreach:
                        do:
                          - process:
                              set:
                                processed: true
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Terminate")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            matches[0].hasForeach shouldBe true
        }

        @Test
        fun `toQueryKey should create key without correlation values`() {
            // Given: A workflow with until filter
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Terminate")

            // When
            val matches = service.findMatchingUntilEvents(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.correlationValuesJson shouldBe null
            queryKey.workflowInfo.name.toString() shouldBe "test-workflow"
        }
    }

    @Nested
    inner class FindMatchingListenTasksTests {

        @Test
        fun `should return empty list when no workflows are cached`() {
            // Given: No workflows in cache
            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event by type`() {
            // Given: A workflow with ONE strategy
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            matches[0].workflowInfo.name.toString() shouldBe "test-workflow"
            matches[0].filterIndex shouldBe 0
        }

        @Test
        fun `should return empty when event type does not match`() {
            // Given: A workflow listening for different event type
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.ExpectedEvent
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.DifferentEvent")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event by subject`() {
            // Given: A workflow with subject filter
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
                              subject: order-123
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event", subject = "order-123")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should not match when subject does not match`() {
            // Given: A workflow with subject filter
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
                              subject: order-123
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event", subject = "order-456")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match multiple filters with correct filterIndex for ALL strategy`() {
            // Given: A workflow with ALL strategy (multiple filters)
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          all:
                            - with:
                                type: com.example.EventA
                            - with:
                                type: com.example.EventB
                            - with:
                                type: com.example.EventC
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            // When: Event B arrives
            val eventB = buildCloudEvent(type = "com.example.EventB")
            val matches = service.findMatchingListenTasks(eventB, eventDataProvider(null))

            // Then: Should match with filterIndex 1
            matches shouldHaveSize 1
            matches[0].filterIndex shouldBe 1
        }

        @Test
        fun `should match same event against multiple filters in ALL strategy`() {
            // Given: A workflow with ALL strategy where same event type appears multiple times
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          all:
                            - with:
                                type: com.example.Event
                            - with:
                                type: com.example.OtherEvent
                            - with:
                                type: com.example.Event
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            // When: Event matching two filters arrives
            val event = buildCloudEvent(type = "com.example.Event")
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then: Should match both filters (index 0 and 2)
            matches shouldHaveSize 2
            matches.map { it.filterIndex }.toSet() shouldBe setOf(0, 2)
        }

        @Test
        fun `should match multiple workflows listening for same event`() {
            // Given: Two workflows listening for the same event
            val yaml1 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-one
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
            """.trimIndent()

            val yaml2 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-two
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
            """.trimIndent()

            WorkflowCache.parseYamlAndPut(yaml1)
            WorkflowCache.parseYamlAndPut(yaml2)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 2
            matches.map { it.workflowInfo.name.toString() } shouldBe listOf("workflow-one", "workflow-two")
        }

        @Test
        fun `should only return matching workflows`() {
            // Given: Multiple workflows, only some match
            val yaml1 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-matches
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
            """.trimIndent()

            val yaml2 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-no-match
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.OtherEvent
            """.trimIndent()

            WorkflowCache.parseYamlAndPut(yaml1)
            WorkflowCache.parseYamlAndPut(yaml2)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            matches[0].workflowInfo.name.toString() shouldBe "workflow-matches"
        }

        @Test
        fun `should return correct workflow info in match result`() {
            // Given: A workflow with specific info
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: my-workflow
                  version: '2.0.0'
                  namespace: production
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            val match = matches[0]
            match.workflowInfo.namespace.toString() shouldBe "production"
            match.workflowInfo.name.toString() shouldBe "my-workflow"
            match.workflowInfo.version.toString() shouldBe "2.0.0"
        }

        @Test
        fun `should include strategy in match result`() {
            // Given: A workflow with ANY strategy
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            matches[0].listenerStrategy shouldBe ListenerStrategy.ANY
        }

        @Test
        fun `should extract correlation values from event data`() {
            // Given: A workflow with correlation defined
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.OrderEvent
                            correlate:
                              orderId:
                                from: .orderId
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.OrderEvent",
                data = """{"orderId": "ORD-123"}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider("""{"orderId": "ORD-123"}"""))

            // Then
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe """{"orderId":"ORD-123"}"""
        }

        @Test
        fun `toQueryKey should create key with correlation values and filterIndex`() {
            // Given: A workflow with correlation
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
                            correlate:
                              customerId:
                                from: .customerId
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"customerId": "CUST-456"}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider("""{"customerId": "CUST-456"}"""))

            // Then
            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.workflowInfo.name.toString() shouldBe "test-workflow"
            queryKey.correlationValuesJson shouldBe """{"customerId":"CUST-456"}"""
            queryKey.filterIndex shouldBe 0
        }

        @Test
        fun `should return null correlationValuesJson when no correlation defined`() {
            // Given: A workflow without correlation
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          one:
                            with:
                              type: com.example.Event
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe null
        }

        @Test
        fun `should match events for ANY with until strategy`() {
            // Given: A workflow with ANY + until event filter
            val yaml = """
                document:
                  dsl: '1.0.0'
                  name: test-workflow
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
                          until:
                            one:
                              with:
                                type: com.example.Terminate
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            // When: Regular event arrives (not termination)
            val event = buildCloudEvent(type = "com.example.Event")
            val matches = service.findMatchingListenTasks(event, eventDataProvider(null))

            // Then: Should match the event filter with ANY_UNTIL_EVENT strategy
            matches shouldHaveSize 1
            matches[0].listenerStrategy shouldBe ListenerStrategy.ANY_UNTIL_EVENT
        }
    }
}
