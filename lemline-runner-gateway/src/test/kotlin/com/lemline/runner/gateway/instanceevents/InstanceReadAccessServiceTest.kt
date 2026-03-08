// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.instanceevents

import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import com.lemline.runner.gateway.instances.InstanceLookupRow
import com.lemline.runner.gateway.instances.InstanceQueryService
import com.lemline.runner.gateway.instances.InstanceStatus
import com.lemline.runner.gateway.instances.InstanceSummaryRow
import io.kotest.assertions.throwables.shouldThrow
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

@DisplayName("InstanceReadAccessService")
class InstanceReadAccessServiceTest {

    private val authorizer = mockk<GatewayAuthorizer>()
    private val instanceQueryService = mockk<InstanceQueryService>()
    private val instanceEventQueryService = mockk<InstanceEventQueryService>()

    private val service = InstanceReadAccessService().apply {
        this.authorizer = this@InstanceReadAccessServiceTest.authorizer
        this.instanceQueryService = this@InstanceReadAccessServiceTest.instanceQueryService
        this.instanceEventQueryService = this@InstanceReadAccessServiceTest.instanceEventQueryService
    }

    private val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")
    private val principal = GatewayPrincipal(
        subject = "test-user",
        scopes = setOf("lemline.watch"),
        namespaces = setOf("*"),
    )

    @BeforeEach
    fun setUp() {
        every { authorizer.requireNamespace(any(), any()) } just runs
    }

    @Test
    fun `authorizeRead uses instance namespace when the instance exists`() = runTest {
        coEvery { instanceQueryService.latestLifecycleSequence() } returns 20L
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns lookup(namespace = "default")

        service.authorizeRead(principal, instanceId)

        verify(exactly = 1) { authorizer.requireNamespace(principal, "default") }
    }

    @Test
    fun `authorizeRead falls back to first event namespace when lookup misses`() = runTest {
        coEvery { instanceQueryService.latestLifecycleSequence() } returns 20L
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns null
        coEvery { instanceEventQueryService.firstEvent(instanceId) } returns eventRow(1L, "WORKFLOW_CREATED", namespace = "team-a")

        service.authorizeRead(principal, instanceId)

        verify(exactly = 1) { authorizer.requireNamespace(principal, "team-a") }
        coVerify(exactly = 1) { instanceEventQueryService.firstEvent(instanceId) }
    }

    @Test
    fun `authorizeRead raises not found when the instance has no state or events`() = runTest {
        coEvery { instanceQueryService.latestLifecycleSequence() } returns 20L
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns null
        coEvery { instanceEventQueryService.firstEvent(instanceId) } returns null

        shouldThrow<GatewayNotFoundException> {
            service.authorizeRead(principal, instanceId)
        }
    }

    @Test
    fun `authorizeRead raises permission denied when the first event has no namespace`() = runTest {
        coEvery { instanceQueryService.latestLifecycleSequence() } returns 20L
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns null
        coEvery { instanceEventQueryService.firstEvent(instanceId) } returns eventRow(1L, "WORKFLOW_CREATED", namespace = "")

        shouldThrow<GatewayPermissionDeniedException> {
            service.authorizeRead(principal, instanceId)
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
        namespace: String?,
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
