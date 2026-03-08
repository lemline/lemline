// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import com.lemline.runner.gateway.config.testLemlineConfiguration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InstanceStreamService")
class InstanceStreamServiceTest {

    private val statsQueryService = mockk<StatsQueryService>()
    private val instanceQueryService = spyk(
        InstanceQueryService(
            config = testLemlineConfiguration(),
        )
    )

    private val service = InstanceStreamService(
        config = testLemlineConfiguration(
            watchPollIntervalMs = 10L,
            watchBatchSize = 100,
        ),
    ).apply {
        this.statsQueryService = this@InstanceStreamServiceTest.statsQueryService
        this.instanceQueryService = this@InstanceStreamServiceTest.instanceQueryService
    }

    @Test
    fun `watchDefinitionStats emits upsert and removed diffs`() = runTest {
        val filter = DefinitionStatsFilter(
            namespace = null,
            name = null,
            version = null,
            timeFrom = Instant.parse("2026-01-01T00:00:00Z"),
            timeTo = Instant.parse("2026-01-02T00:00:00Z"),
        )
        val oldRow = statsRow("team-a", "orders", "1.0.0", started = 1)
        val newRow = statsRow("team-b", "payments", "2.0.0", started = 3)

        coEvery { instanceQueryService.latestSequence() } returnsMany listOf(10L, 11L)
        coEvery { statsQueryService.queryDefinitionStatsSnapshot(filter, 10L) } returns listOf(oldRow)
        coEvery { statsQueryService.queryDefinitionStatsSnapshot(filter, 11L) } returns listOf(newRow)

        val received = mutableListOf<DefinitionStatsChangeRow>()
        service.watchDefinitionStats(filter, afterSequence = null) { change ->
            received += change
            received.size < 2
        }

        received.shouldContainExactly(
            DefinitionStatsChangeRow(
                cursor = 11L,
                changeType = ChangeType.REMOVED,
                stats = oldRow,
            ),
            DefinitionStatsChangeRow(
                cursor = 11L,
                changeType = ChangeType.UPSERT,
                stats = newRow,
            ),
        )
    }

    @Test
    fun `watchInstances emits removed when an instance stops matching`() = runTest {
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")
        val filter = InstanceListFilter(status = InstanceStatus.RUNNING)

        coEvery {
            instanceQueryService.listChangedInstanceSequencesAfter(10L, 100, filter)
        } returns listOf(WorkflowEventSequence(sequence = 11L, workflowId = instanceId))
        coEvery { instanceQueryService.findInstance(instanceId, 10L) } returns lookup(status = InstanceStatus.RUNNING)
        coEvery { instanceQueryService.findInstance(instanceId, 11L) } returns lookup(status = InstanceStatus.COMPLETED)

        val received = mutableListOf<InstanceChangeRow>()
        service.watchInstances(filter, afterSequence = 10L) { change ->
            received += change
            false
        }

        received.single().changeType shouldBe ChangeType.REMOVED
        received.single().instance.status shouldBe InstanceStatus.COMPLETED
    }

    @Test
    fun `watchInstances emits upsert when an instance enters the filter`() = runTest {
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c32")
        val filter = InstanceListFilter(status = InstanceStatus.RUNNING)

        coEvery {
            instanceQueryService.listChangedInstanceSequencesAfter(20L, 100, filter)
        } returns listOf(WorkflowEventSequence(sequence = 21L, workflowId = instanceId))
        coEvery { instanceQueryService.findInstance(instanceId, 20L) } returns lookup(status = InstanceStatus.PENDING)
        coEvery { instanceQueryService.findInstance(instanceId, 21L) } returns lookup(status = InstanceStatus.RUNNING)

        val received = mutableListOf<InstanceChangeRow>()
        service.watchInstances(filter, afterSequence = 20L) { change ->
            received += change
            false
        }

        received.single().changeType shouldBe ChangeType.UPSERT
        received.single().instance.status shouldBe InstanceStatus.RUNNING
    }

    private fun statsRow(
        namespace: String,
        name: String,
        version: String,
        started: Long,
    ) = DefinitionStatsRow(
        namespace = namespace,
        name = name,
        version = version,
        startedCount = started,
        completedCount = 0,
        faultedCount = 0,
        runningCountAtTimeTo = 0,
    )

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
