// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instanceevents

import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.time.Instant
import java.util.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InstanceEventWatchService")
class InstanceEventWatchServiceTest {

    private val authorizer = mockk<GatewayAuthorizer>()
    private val instanceReadAccessService = mockk<InstanceReadAccessService>()
    private val instanceEventQueryService = mockk<InstanceEventQueryService>()

    private val batchSize = 100

    private val service = InstanceEventWatchService(
        config = testLemlineConfiguration(
            watchPollIntervalMs = 10L,
            watchBatchSize = batchSize,
        ),
    ).apply {
        this.authorizer = this@InstanceEventWatchServiceTest.authorizer
        this.instanceReadAccessService = this@InstanceEventWatchServiceTest.instanceReadAccessService
        this.instanceEventQueryService = this@InstanceEventWatchServiceTest.instanceEventQueryService
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
        coEvery { instanceReadAccessService.authorizeRead(any(), any()) } just runs
    }

    @Test
    fun `missing scope throws permission denied`() = runTest {
        every { authorizer.requireScope(principal, "lemline.watch") } throws
            GatewayPermissionDeniedException("Missing required scope 'lemline.watch'")

        shouldThrow<GatewayPermissionDeniedException> {
            service.watch(instanceId, principal) { true }
        }
    }

    @Test
    fun `watch streams events in order`() = runTest {
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

        coVerify(exactly = 1) { instanceReadAccessService.authorizeRead(principal, instanceId) }
        received.shouldContainExactly("WORKFLOW_CREATED", "WORKFLOW_STARTED", "WORKFLOW_COMPLETED")
    }

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
