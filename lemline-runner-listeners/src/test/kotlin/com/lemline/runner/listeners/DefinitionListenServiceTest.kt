// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.core.workflows.WorkflowCache
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

    @Nested
    inner class FindMatchingUntilEventsTests {

        @Test
        fun `should return empty list when no workflows are cached`() {
            // Given: No workflows in cache
            val event = buildCloudEvent(type = "com.example.Terminate")

            // When
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(event)

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should return empty list when workflow has until expression not event`() {
            // Given: A workflow with ANY + until expression (not event filter)
            val yaml = $$"""
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
                          until: '${ .count >= 5 }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(nonMatchingEvent)

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
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingUntilEvents(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(eventB)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

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
            val matches = service.findMatchingListenTasks(event)

            // Then: Should match the event filter with ANY_UNTIL_EVENT strategy
            matches shouldHaveSize 1
            matches[0].listenerStrategy shouldBe ListenerStrategy.ANY_UNTIL_EVENT
        }
    }

    @Nested
    inner class AdvancedFilterMatchingTests {

        @Test
        fun `should match event by id`() {
            // Given: A workflow with id filter
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
                              id: specific-event-id
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = CloudEventBuilder.v1()
                .withId("specific-event-id")
                .withType("com.example.Event")
                .withSource(URI.create("https://test.example.com"))
                .build()

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should not match when id does not match`() {
            // Given: A workflow with id filter
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
                              id: expected-id
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = CloudEventBuilder.v1()
                .withId("different-id")
                .withType("com.example.Event")
                .withSource(URI.create("https://test.example.com"))
                .build()

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event by datacontenttype`() {
            // Given: A workflow with datacontenttype filter
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
                              datacontenttype: application/json
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = CloudEventBuilder.v1()
                .withId("test-id")
                .withType("com.example.Event")
                .withSource(URI.create("https://test.example.com"))
                .withDataContentType("application/json")
                .build()

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should not match when datacontenttype does not match`() {
            // Given: A workflow with datacontenttype filter
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
                              datacontenttype: application/json
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = CloudEventBuilder.v1()
                .withId("test-id")
                .withType("com.example.Event")
                .withSource(URI.create("https://test.example.com"))
                .withDataContentType("text/plain")
                .build()

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event by source as string`() {
            // Given: A workflow with source filter
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
                              source: https://specific.source.com
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                source = "https://specific.source.com"
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should not match when source does not match`() {
            // Given: A workflow with source filter
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
                              source: https://expected.source.com
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                source = "https://different.source.com"
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event with source expression`() {
            // Given: A workflow with source expression filter
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
                              source: '${'$'}{ . == "https://test.example.com" }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                source = "https://test.example.com"
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should not match when source expression evaluates to false`() {
            // Given: A workflow with source expression
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
                              source: '${'$'}{ . == "https://expected.com" }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                source = "https://different.com"
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event with data expression`() {
            // Given: A workflow with data expression filter
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
                              data: '${'$'}{ .amount > 100 }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"amount": 150}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should not match when data expression evaluates to false`() {
            // Given: A workflow with data expression filter
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
                              data: '${'$'}{ .amount > 100 }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"amount": 50}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match event with literal data value`() {
            // Given: A workflow with literal data filter
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
                              data: '{"status":"active"}'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"status":"active"}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should not match when literal data value differs`() {
            // Given: A workflow with literal data filter
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
                              data: '{"status":"active"}'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"status":"inactive"}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches.shouldBeEmpty()
        }

        @Test
        fun `should handle invalid literal data filter gracefully`() {
            // Given: A workflow with invalid literal JSON in data filter
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
                              data: '{invalid json'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"status":"active"}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then: Should not match (invalid filter never matches)
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match all filter attributes when specified`() {
            // Given: A workflow with multiple filter attributes
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
                              source: https://test.example.com
                              subject: order-123
                              datacontenttype: application/json
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = CloudEventBuilder.v1()
                .withId("test-id")
                .withType("com.example.Event")
                .withSource(URI.create("https://test.example.com"))
                .withSubject("order-123")
                .withDataContentType("application/json")
                .build()

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should not match when one of multiple filter attributes does not match`() {
            // Given: A workflow with multiple filter attributes
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
                              source: https://test.example.com
                              subject: order-123
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                source = "https://test.example.com",
                subject = "order-456"
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches.shouldBeEmpty()
        }
    }

    @Nested
    inner class ComplexCorrelationTests {

        @Test
        fun `should extract multiple correlation keys`() {
            // Given: A workflow with multiple correlation keys
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
                              customerId:
                                from: .customerId
                              region:
                                from: .region
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.OrderEvent",
                data = """{"orderId": "ORD-123", "customerId": "CUST-456", "region": "EU"}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe """{"customerId":"CUST-456","orderId":"ORD-123","region":"EU"}"""
        }

        @Test
        fun `should sort correlation keys alphabetically`() {
            // Given: A workflow with correlation keys in non-alphabetical order
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
                              zebra:
                                from: .z
                              alpha:
                                from: .a
                              middle:
                                from: .m
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"z": "Z", "a": "A", "m": "M"}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe """{"alpha":"A","middle":"M","zebra":"Z"}"""
        }

        @Test
        fun `should handle JQ expression in correlate from`() {
            // Given: A workflow with JQ expression in correlation
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
                              userId:
                                from: '${'$'}{ .user.id }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"user": {"id": "USER-789"}}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe """{"userId":"USER-789"}"""
        }

        @Test
        fun `should handle complex JQ expression in correlate from`() {
            // Given: A workflow with complex JQ expression
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
                              orderId:
                                from: '${'$'}{ .items[0].orderId }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"items": [{"orderId": "ORD-999"}]}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe """{"orderId":"ORD-999"}"""
        }

        @Test
        fun `should return null correlation when expression evaluation fails`() {
            // Given: A workflow with correlation expression that will fail
            val yaml = $$"""
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
                              userId:
                                from: '${ .nonexistent.field }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent("com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then: Should still match event, but correlation is null
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe null
        }

        @Test
        fun `should skip correlation key when value is null`() {
            // Given: A workflow with correlation on potentially null field
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
                              userId:
                                from: .userId
                              optionalField:
                                from: .optional
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"userId": "USER-123"}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then: Should only include non-null values
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe """{"userId":"USER-123"}"""
        }

        @Test
        fun `should handle non-string correlation values by converting to string`() {
            // Given: A workflow with correlation on numeric field
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
                              orderNumber:
                                from: .orderNumber
                              isActive:
                                from: .active
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"orderNumber": 12345, "active": true}"""
            )

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then: Should convert non-string values to strings
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldNotBe null
        }

        @Test
        fun `should return null when all correlation keys extract null values`() {
            // Given: A workflow with correlation on missing fields
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
                              field1:
                                from: .missing1
                              field2:
                                from: .missing2
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent("com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
            matches[0].correlationValuesJson shouldBe null
        }

    }

    @Nested
    inner class EdgeCasesAndErrorHandlingTests {

        @Test
        fun `should handle event with null source gracefully`() {
            // Given: A workflow listening for any event
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

            val event = CloudEventBuilder.v1()
                .withId("test-id")
                .withType("com.example.Event")
                .withSource(URI.create("https://test.example.com"))
                .build()

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then: Should match
            matches shouldHaveSize 1
        }

        @Test
        fun `should handle event with null subject gracefully`() {
            // Given: A workflow without subject filter
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
            val matches = service.findMatchingListenTasks(event)

            // Then
            matches shouldHaveSize 1
        }

        @Test
        fun `should handle data expression error gracefully and not match`() {
            // Given: A workflow with invalid data expression
            val yaml = $$"""
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
                              data: '${ invalid jq syntax }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent("com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then: Should not match (expression evaluation failed)
            matches.shouldBeEmpty()
        }

        @Test
        fun `should not evaluate event data provider when filter does not match`() {
            // Given: A workflow listening for a different event type
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
                              type: com.example.DifferentEvent
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")

            var dataProviderCalled = false
            val lazyDataProvider = {
                dataProviderCalled = true
                Json.parseToJsonElement("{}")
            }

            // When
            val matches = service.findMatchingListenTasks(event, lazyDataProvider)

            // Then: Should not match, and data provider should NOT be called
            matches.shouldBeEmpty()
            dataProviderCalled shouldBe false
        }

        @Test
        fun `should evaluate event data provider when data filter is present`() {
            // Given: A workflow with data filter
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
                              data: '${'$'}{ .status == "active" }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent("com.example.Event")

            var dataProviderCalled = false
            val lazyDataProvider = {
                dataProviderCalled = true
                Json.parseToJsonElement("""{"status": "active"}""")
            }

            // When
            val matches = service.findMatchingListenTasks(event, lazyDataProvider)

            // Then: Should match and data provider SHOULD be called
            matches shouldHaveSize 1
            dataProviderCalled shouldBe true
        }

        @Test
        fun `should evaluate event data provider when correlation is present`() {
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
                              userId:
                                from: .userId
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent("com.example.Event")

            var dataProviderCalled = false
            val lazyDataProvider = {
                dataProviderCalled = true
                Json.parseToJsonElement("""{"userId": "USER-123"}""")
            }

            // When
            val matches = service.findMatchingListenTasks(event, lazyDataProvider)

            // Then: Should match and data provider SHOULD be called for correlation
            matches shouldHaveSize 1
            dataProviderCalled shouldBe true
        }

        @Test
        fun `should handle expression returning non-boolean as false`() {
            // Given: A workflow with source expression returning a string
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
                              source: '${'$'}{ . }'
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent("com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then: Non-boolean result treated as false, no match
            matches.shouldBeEmpty()
        }

        @Test
        fun `should match multiple workflows in cache`() {
            // Given: Multiple workflows cached
            val yaml1 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-1
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
                  name: workflow-2
                  version: '2.0.0'
                  namespace: production
                do:
                  - waitForEvents:
                      listen:
                        to:
                          any:
                            - with:
                                type: com.example.Event
            """.trimIndent()

            val yaml3 = """
                document:
                  dsl: '1.0.0'
                  name: workflow-3
                  version: '1.0.0'
                  namespace: default
                do:
                  - waitForEvents:
                      listen:
                        to:
                          all:
                            - with:
                                type: com.example.Event
            """.trimIndent()

            WorkflowCache.parseYamlAndPut(yaml1)
            WorkflowCache.parseYamlAndPut(yaml2)
            WorkflowCache.parseYamlAndPut(yaml3)

            val event = buildCloudEvent(type = "com.example.Event")

            // When
            val matches = service.findMatchingListenTasks(event)

            // Then: All three workflows should match
            matches shouldHaveSize 3
        }
    }

    @Nested
    inner class ToQueryKeyTests {

        @Test
        fun `toQueryKey should use filterIndex 0 for ONE strategy`() {
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
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.filterIndex shouldBe 0
            queryKey.listenerStrategy shouldBe ListenerStrategy.ONE
        }

        @Test
        fun `toQueryKey should use filterIndex 0 for ANY strategy`() {
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
                                type: com.example.Event1
                            - with:
                                type: com.example.Event2
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event1")
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.filterIndex shouldBe 0
            queryKey.listenerStrategy shouldBe ListenerStrategy.ANY
        }

        @Test
        fun `toQueryKey should use actual filterIndex for ALL strategy - first filter`() {
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
                                type: com.example.Event1
                            - with:
                                type: com.example.Event2
                            - with:
                                type: com.example.Event3
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event1")
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.filterIndex shouldBe 0
            queryKey.listenerStrategy shouldBe ListenerStrategy.ALL
        }

        @Test
        fun `toQueryKey should use actual filterIndex for ALL strategy - second filter`() {
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
                                type: com.example.Event1
                            - with:
                                type: com.example.Event2
                            - with:
                                type: com.example.Event3
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event2")
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.filterIndex shouldBe 1
            queryKey.listenerStrategy shouldBe ListenerStrategy.ALL
        }

        @Test
        fun `toQueryKey should use actual filterIndex for ALL strategy - third filter`() {
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
                                type: com.example.Event1
                            - with:
                                type: com.example.Event2
                            - with:
                                type: com.example.Event3
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event3")
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.filterIndex shouldBe 2
            queryKey.listenerStrategy shouldBe ListenerStrategy.ALL
        }

        @Test
        fun `toQueryKey should use null filterIndex for ANY_UNTIL_EVENT strategy`() {
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

            val event = buildCloudEvent(type = "com.example.Event")
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.filterIndex shouldBe null
            queryKey.listenerStrategy shouldBe ListenerStrategy.ANY_UNTIL_EVENT
        }

        @Test
        fun `toQueryKey should use null filterIndex for ANY_UNTIL_EXPR strategy`() {
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
                          until: .count >= 5
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(type = "com.example.Event")
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.filterIndex shouldBe null
            queryKey.listenerStrategy shouldBe ListenerStrategy.ANY_UNTIL_EXPR
        }

        @Test
        fun `toQueryKey should preserve correlation values`() {
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
                              orderId:
                                from: .orderId
                              customerId:
                                from: .customerId
            """.trimIndent()
            WorkflowCache.parseYamlAndPut(yaml)

            val event = buildCloudEvent(
                type = "com.example.Event",
                data = """{"orderId": "ORD-123", "customerId": "CUST-456"}"""
            )
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.correlationValuesJson shouldNotBe null

            val correlationJson = Json.parseToJsonElement(queryKey.correlationValuesJson!!)
            val orderId = correlationJson.jsonObject["orderId"]?.toString()?.trim('"')
            val customerId = correlationJson.jsonObject["customerId"]?.toString()?.trim('"')
            orderId shouldBe "ORD-123"
            customerId shouldBe "CUST-456"
        }

        @Test
        fun `toQueryKey should have null correlation when no correlation defined`() {
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
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.correlationValuesJson shouldBe null
        }

        @Test
        fun `toQueryKey should preserve workflow info`() {
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
            val matches = service.findMatchingListenTasks(event)

            matches shouldHaveSize 1
            val queryKey = matches[0].toQueryKey()
            queryKey.workflowInfo.name.toString() shouldBe "my-workflow"
            queryKey.workflowInfo.version.toString() shouldBe "2.0.0"
            queryKey.workflowInfo.namespace.toString() shouldBe "production"
        }
    }

}
