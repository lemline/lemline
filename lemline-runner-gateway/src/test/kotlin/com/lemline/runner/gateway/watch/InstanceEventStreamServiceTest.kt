// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.watch

import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.config.testLemlineConfiguration
import com.lemline.runner.gateway.dashboard.InstanceEventListResult
import com.lemline.runner.gateway.dashboard.InstanceEventQueryService
import com.lemline.runner.gateway.dashboard.InstanceEventRow
import com.lemline.runner.gateway.dashboard.InstanceLookupRow
import com.lemline.runner.gateway.dashboard.InstanceQueryService
import com.lemline.runner.gateway.dashboard.InstanceStatus
import com.lemline.runner.gateway.dashboard.InstanceSummaryRow
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InstanceEventStreamService")
class InstanceEventStreamServiceTest {

    private val authorizer = mockk<GatewayAuthorizer>()
    private val instanceQueryService = mockk<InstanceQueryService>()
    private val instanceEventQueryService = mockk<InstanceEventQueryService>()

    private val pollIntervalMs = 10L
    private val batchSize = 100

    private val service = InstanceEventStreamService(
        config = testLemlineConfiguration(
            watchPollIntervalMs = pollIntervalMs,
            watchBatchSize = batchSize,
        ),
    ).apply {
        this.authorizer = this@InstanceEventStreamServiceTest.authorizer
        this.instanceQueryService = this@InstanceEventStreamServiceTest.instanceQueryService
        this.instanceEventQueryService = this@InstanceEventStreamServiceTest.instanceEventQueryService
    }

    private val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")
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

    @Test
    fun `missing scope throws PermissionDeniedException`() = runTest {
        every { authorizer.requireScope(principal, "lemline.watch") } throws
            GatewayPermissionDeniedException("Missing required scope 'lemline.watch'")

        shouldThrow<GatewayPermissionDeniedException> {
            service.watch(instanceId, principal) { true }
        }
    }

    @Test
    fun `streams events in order after authorizing existing instance namespace`() = runTest {
        coEvery { instanceQueryService.latestSequence() } returns 20L
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns lookup(namespace = "default")
        coEvery {
            instanceEventQueryService.listEvents(match {
                it.instanceId == instanceId && it.afterSequenceExclusive == null && it.pageSize == batchSize
            })
        } returns InstanceEventListResult(
            events = listOf(
                eventRow(1L, "WORKFLOW_CREATED"),
                eventRow(2L, "WORKFLOW_STARTED"),
            ),
            nextCursor = null,
            latestCursor = 2L,
        )
        coEvery {
            instanceEventQueryService.listEvents(match {
                it.instanceId == instanceId && it.afterSequenceExclusive == 2L && it.pageSize == batchSize
            })
        } returns InstanceEventListResult(
            events = listOf(eventRow(3L, "WORKFLOW_COMPLETED")),
            nextCursor = null,
            latestCursor = 3L,
        )

        val received = mutableListOf<String>()
        service.watch(instanceId, principal) { row ->
            received += row.eventType
            row.cursor < 3L
        }

        verify(exactly = 1) { authorizer.requireNamespace(principal, "default") }
        received.shouldContainExactly("WORKFLOW_CREATED", "WORKFLOW_STARTED", "WORKFLOW_COMPLETED")
    }

    @Test
    fun `when instance lookup misses, first event namespace is used for authorization`() = runTest {
        coEvery { instanceQueryService.latestSequence() } returns 20L
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns null
        coEvery { instanceEventQueryService.firstEvent(instanceId) } returns eventRow(1L, "WORKFLOW_CREATED", namespace = "team-a")
        coEvery {
            instanceEventQueryService.listEvents(match {
                it.instanceId == instanceId && it.afterSequenceExclusive == 10L
            })
        } returns InstanceEventListResult(
            events = listOf(eventRow(11L, "WORKFLOW_STARTED", namespace = "team-a")),
            nextCursor = null,
            latestCursor = 11L,
        )

        service.watch(instanceId, principal, afterCursorExclusive = 10L) { false }

        verify(exactly = 1) { authorizer.requireNamespace(principal, "team-a") }
        coVerify(exactly = 1) { instanceEventQueryService.firstEvent(instanceId) }
    }

    @Test
    fun `missing instance raises not found`() = runTest {
        coEvery { instanceQueryService.latestSequence() } returns 20L
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns null
        coEvery { instanceEventQueryService.firstEvent(instanceId) } returns null

        shouldThrow<GatewayNotFoundException> {
            service.watch(instanceId, principal) { true }
        }
    }

    private fun lookup(namespace: String): InstanceLookupRow =
        InstanceLookupRow(
            instance = InstanceSummaryRow(
                instanceId = instanceId,
                namespace = namespace,
                name = "orders",
                version = "1.0.0",
                status = InstanceStatus.RUNNING,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                startedAt = Instant.parse("2026-01-01T00:00:01Z"),
                completedAt = null,
                faultedAt = null,
                durationMs = null,
                error = null,
            ),
            latestEventSequence = 20L,
        )

    private fun eventRow(
        cursor: Long,
        eventType: String,
        namespace: String = "default",
    ): InstanceEventRow =
        InstanceEventRow(
            cursor = cursor,
            instanceId = instanceId,
            eventType = eventType,
            eventTime = Instant.parse("2026-01-01T00:00:00Z"),
            cloudEventJson = """{"type":"$eventType"}""",
            namespace = namespace,
        )
}
