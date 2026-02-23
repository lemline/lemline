// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.watch

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowNamespace
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventRow
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.config.testLemlineConfiguration
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import com.lemline.runner.gateway.outbox.GatewayCommandOutboxModel
import com.lemline.runner.gateway.outbox.GatewayCommandOutboxRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WorkflowWatchService")
class WorkflowWatchServiceTest {

    private val authorizer = mockk<GatewayAuthorizer>()
    private val outboxRepository = mockk<GatewayCommandOutboxRepository>()
    private val eventSource = mockk<WorkflowAnalyticsEventSource>()

    private val pollIntervalMs = 10L
    private val batchSize = 100

    private val service = WorkflowWatchService(
        config = testLemlineConfiguration(
            watchPollIntervalMs = pollIntervalMs,
            watchBatchSize = batchSize,
        ),
    ).apply {
        this.authorizer = this@WorkflowWatchServiceTest.authorizer
        this.gatewayOutboxRepository = this@WorkflowWatchServiceTest.outboxRepository
        this.eventSource = this@WorkflowWatchServiceTest.eventSource
    }

    private val workflowId = WorkflowId.random()
    private val principal = GatewayPrincipal(
        subject = "test-user",
        scopes = setOf("lemline.watch"),
        namespaces = setOf("*"),
    )

    @BeforeEach
    fun setUp() {
        every { authorizer.requireScope(any(), any()) } just runs
        every { authorizer.requireNamespace(any(), any()) } just runs
    }

    @Nested
    @DisplayName("Authorization")
    inner class Authorization {

        @Test
        fun `missing scope throws PermissionDeniedException`() = runTest {
            every { authorizer.requireScope(principal, "lemline.watch") } throws
                GatewayPermissionDeniedException("Missing required scope 'lemline.watch'")

            shouldThrow<GatewayPermissionDeniedException> {
                service.watch(workflowId, principal) { true }
            }
        }

        @Test
        fun `unauthorized namespace throws PermissionDeniedException`() = runTest {
            val outboxEntry = mockk<GatewayCommandOutboxModel> {
                every { workflowNamespace } returns WorkflowNamespace("restricted")
            }
            coEvery { outboxRepository.findByWorkflowId(workflowId) } returns outboxEntry
            every { authorizer.requireNamespace(principal, "restricted") } throws
                GatewayPermissionDeniedException("Not authorized for namespace 'restricted'")

            shouldThrow<GatewayPermissionDeniedException> {
                service.watch(workflowId, principal) { true }
            }
        }
    }

    @Nested
    @DisplayName("Outbox polling")
    inner class OutboxPolling {

        @Test
        fun `outbox entry found immediately proceeds to event polling`() = runTest {
            val outboxEntry = mockk<GatewayCommandOutboxModel> {
                every { workflowNamespace } returns WorkflowNamespace("default")
            }
            coEvery { outboxRepository.findByWorkflowId(workflowId) } returns outboxEntry
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) } returns listOf(
                WorkflowAnalyticsEventRow(cursor = 1, eventType = "WORKFLOW_STARTED", cloudEventJson = "{}")
            )

            val received = mutableListOf<WorkflowAnalyticsEventRow>()
            service.watch(workflowId, principal) { row ->
                received.add(row)
                false // stop after first event
            }

            received.size shouldBe 1
            received[0].eventType shouldBe "WORKFLOW_STARTED"
            coVerify(exactly = 1) { outboxRepository.findByWorkflowId(workflowId) }
        }

        @Test
        fun `outbox entry found after retries`() = runTest {
            val outboxEntry = mockk<GatewayCommandOutboxModel> {
                every { workflowNamespace } returns WorkflowNamespace("default")
            }
            coEvery { outboxRepository.findByWorkflowId(workflowId) } returnsMany listOf(null, null, outboxEntry)
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) } returns listOf(
                WorkflowAnalyticsEventRow(cursor = 1, eventType = "WORKFLOW_STARTED", cloudEventJson = "{}")
            )

            service.watch(workflowId, principal) { false }

            coVerify(exactly = 3) { outboxRepository.findByWorkflowId(workflowId) }
        }
    }

    @Nested
    @DisplayName("Event streaming")
    inner class EventStreaming {

        private val outboxEntry = mockk<GatewayCommandOutboxModel> {
            every { workflowNamespace } returns WorkflowNamespace("default")
        }

        @BeforeEach
        fun setUpOutbox() {
            coEvery { outboxRepository.findByWorkflowId(workflowId) } returns outboxEntry
        }

        @Test
        fun `events streamed in order with correct cursor progression`() = runTest {
            val events = listOf(
                WorkflowAnalyticsEventRow(cursor = 1, eventType = "WORKFLOW_STARTED", cloudEventJson = "{}"),
                WorkflowAnalyticsEventRow(cursor = 2, eventType = "TASK_STARTED", cloudEventJson = "{}"),
                WorkflowAnalyticsEventRow(cursor = 3, eventType = "TASK_COMPLETED", cloudEventJson = "{}"),
            )
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) } returns events
            // After consuming cursor=3, the next poll should use afterSequenceExclusive=3
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 3L, batchSize) } returns listOf(
                WorkflowAnalyticsEventRow(cursor = 4, eventType = "WORKFLOW_COMPLETED", cloudEventJson = "{}")
            )

            val received = mutableListOf<String>()
            service.watch(workflowId, principal) { row ->
                received.add(row.eventType)
                row.cursor < 4 // stop after cursor 4
            }

            received.shouldContainExactly(
                "WORKFLOW_STARTED", "TASK_STARTED", "TASK_COMPLETED", "WORKFLOW_COMPLETED"
            )
        }

        @Test
        fun `empty poll then events`() = runTest {
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) } returnsMany listOf(
                emptyList(),
                listOf(WorkflowAnalyticsEventRow(cursor = 1, eventType = "WORKFLOW_STARTED", cloudEventJson = "{}"))
            )

            service.watch(workflowId, principal) { false }

            coVerify(exactly = 2) { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) }
        }

        @Test
        fun `client disconnect via onEvent returning false stops watch`() = runTest {
            val events = listOf(
                WorkflowAnalyticsEventRow(cursor = 1, eventType = "WORKFLOW_STARTED", cloudEventJson = "{}"),
                WorkflowAnalyticsEventRow(cursor = 2, eventType = "TASK_STARTED", cloudEventJson = "{}"),
                WorkflowAnalyticsEventRow(cursor = 3, eventType = "TASK_COMPLETED", cloudEventJson = "{}"),
            )
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) } returns events

            val received = mutableListOf<String>()
            service.watch(workflowId, principal) { row ->
                received.add(row.eventType)
                row.eventType != "TASK_STARTED" // disconnect on TASK_STARTED
            }

            // Should have received only first two events; third was never delivered
            received.shouldContainExactly("WORKFLOW_STARTED", "TASK_STARTED")
            // No further polling after disconnect
            coVerify(exactly = 1) { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) }
        }

        @Test
        fun `terminal events are streamed like any other event`() = runTest {
            val events = listOf(
                WorkflowAnalyticsEventRow(cursor = 1, eventType = "WORKFLOW_STARTED", cloudEventJson = "{}"),
                WorkflowAnalyticsEventRow(cursor = 2, eventType = "WORKFLOW_COMPLETED", cloudEventJson = "{}"),
            )
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) } returns events
            // After the "terminal" events, polling continues with the advanced cursor
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 2L, batchSize) } returns listOf(
                WorkflowAnalyticsEventRow(cursor = 3, eventType = "EXTRA_EVENT", cloudEventJson = "{}")
            )

            val received = mutableListOf<String>()
            service.watch(workflowId, principal) { row ->
                received.add(row.eventType)
                row.cursor < 3 // stop after cursor 3
            }

            // Stream continued past WORKFLOW_COMPLETED
            received.shouldContainExactly("WORKFLOW_STARTED", "WORKFLOW_COMPLETED", "EXTRA_EVENT")
        }

        @Test
        fun `multiple batches advance cursor correctly`() = runTest {
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) } returns listOf(
                WorkflowAnalyticsEventRow(cursor = 10, eventType = "E1", cloudEventJson = "{}"),
                WorkflowAnalyticsEventRow(cursor = 20, eventType = "E2", cloudEventJson = "{}"),
            )
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 20L, batchSize) } returns listOf(
                WorkflowAnalyticsEventRow(cursor = 30, eventType = "E3", cloudEventJson = "{}"),
            )
            coEvery { eventSource.listByWorkflowIdAfter(workflowId, 30L, batchSize) } returns listOf(
                WorkflowAnalyticsEventRow(cursor = 40, eventType = "E4", cloudEventJson = "{}"),
            )

            val received = mutableListOf<String>()
            service.watch(workflowId, principal) { row ->
                received.add(row.eventType)
                row.cursor < 40
            }

            received.shouldContainExactly("E1", "E2", "E3", "E4")
            coVerify(exactly = 1) { eventSource.listByWorkflowIdAfter(workflowId, 0L, batchSize) }
            coVerify(exactly = 1) { eventSource.listByWorkflowIdAfter(workflowId, 20L, batchSize) }
            coVerify(exactly = 1) { eventSource.listByWorkflowIdAfter(workflowId, 30L, batchSize) }
        }
    }
}
