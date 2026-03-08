// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instances

import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.spyk
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InstanceWatchService")
class InstanceWatchServiceTest {

    private val instanceQueryService = spyk(
        InstanceQueryService(
            config = testLemlineConfiguration(),
        )
    )

    private val service = InstanceWatchService(
        config = testLemlineConfiguration(
            watchPollIntervalMs = 10L,
            watchBatchSize = 100,
        ),
    ).apply {
        this.instanceQueryService = this@InstanceWatchServiceTest.instanceQueryService
    }

    @Test
    fun `watch emits removed when an instance stops matching`() = runTest {
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")
        val filter = InstanceListFilter(status = InstanceStatus.RUNNING)

        coEvery {
            instanceQueryService.listChangedInstanceSequencesAfter(10L, 100, filter)
        } returns listOf(WorkflowEventSequence(sequence = 11L, workflowId = instanceId))
        coEvery { instanceQueryService.findInstance(instanceId, 10L) } returns lookup(status = InstanceStatus.RUNNING)
        coEvery { instanceQueryService.findInstance(instanceId, 11L) } returns lookup(status = InstanceStatus.COMPLETED)

        val received = mutableListOf<InstanceChangeRow>()
        service.watch(filter, afterSequence = 10L) { change ->
            received += change
            false
        }

        received.single().changeType shouldBe InstanceChangeKind.REMOVED
        received.single().instance.status shouldBe InstanceStatus.COMPLETED
    }

    @Test
    fun `watch emits upsert when an instance enters the filter`() = runTest {
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c32")
        val filter = InstanceListFilter(status = InstanceStatus.RUNNING)

        coEvery {
            instanceQueryService.listChangedInstanceSequencesAfter(20L, 100, filter)
        } returns listOf(WorkflowEventSequence(sequence = 21L, workflowId = instanceId))
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns lookup(status = InstanceStatus.PENDING)
        coEvery { instanceQueryService.findInstance(instanceId, 21L) } returns lookup(status = InstanceStatus.RUNNING)

        val received = mutableListOf<InstanceChangeRow>()
        service.watch(filter, afterSequence = 20L) { change ->
            received += change
            false
        }

        received.single().changeType shouldBe InstanceChangeKind.UPSERT
        received.single().instance.status shouldBe InstanceStatus.RUNNING
    }

    private fun lookup(status: InstanceStatus) = InstanceLookupRow(
        instance = InstanceSummaryRow(
            instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31"),
            namespace = "team-a",
            name = "orders",
            version = "1.0.0",
            status = status,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            startedAt = Instant.parse("2026-01-01T00:00:01Z"),
            completedAt = if (status == InstanceStatus.COMPLETED) Instant.parse("2026-01-01T00:00:03Z") else null,
            faultedAt = null,
            durationMs = if (status == InstanceStatus.COMPLETED) 2_000L else null,
            error = null,
        ),
        latestEventSequence = 21L,
    )
}
