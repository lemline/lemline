// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitionstats

import com.lemline.runner.gateway.resources.instances.InstanceQueryService
import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefinitionStatsWatchService")
class DefinitionStatsWatchServiceTest {

    private val definitionStatsQueryService = mockk<DefinitionStatsQueryService>()
    private val instanceQueryService = mockk<InstanceQueryService>()

    private val service = DefinitionStatsWatchService(
        config = testLemlineConfiguration(watchPollIntervalMs = 10L),
    ).apply {
        this.definitionStatsQueryService = this@DefinitionStatsWatchServiceTest.definitionStatsQueryService
        this.instanceQueryService = this@DefinitionStatsWatchServiceTest.instanceQueryService
    }

    @Test
    fun `watch emits upsert and removed diffs`() = runTest {
        val filter = DefinitionStatsFilter(
            namespace = null,
            name = null,
            version = null,
            timeFrom = Instant.parse("2026-01-01T00:00:00Z"),
            timeTo = Instant.parse("2026-01-02T00:00:00Z"),
        )
        val oldRow = statsRow("team-a", "orders", "1.0.0", started = 1)
        val newRow = statsRow("team-b", "payments", "2.0.0", started = 3)

        coEvery { instanceQueryService.latestLifecycleSequence() } returnsMany listOf(10L, 11L)
        coEvery { definitionStatsQueryService.queryDefinitionStatsSnapshot(filter, 10L) } returns listOf(oldRow)
        coEvery { definitionStatsQueryService.queryDefinitionStatsSnapshot(filter, 11L) } returns listOf(newRow)

        val received = mutableListOf<DefinitionStatsChangeRow>()
        service.watch(filter, afterSequence = null) { change ->
            received += change
            received.size < 2
        }

        received.shouldContainExactly(
            DefinitionStatsChangeRow(
                cursor = 10L,
                changeType = DefinitionStatsChangeKind.REMOVED,
                stats = oldRow,
            ),
            DefinitionStatsChangeRow(
                cursor = 11L,
                changeType = DefinitionStatsChangeKind.UPSERT,
                stats = newRow,
            ),
        )
    }

    @Test
    fun `resuming from a change in the middle of a poll replays the rest of that poll`() = runTest {
        val filter = DefinitionStatsFilter(
            namespace = null,
            name = null,
            version = null,
            timeFrom = Instant.parse("2026-01-01T00:00:00Z"),
            timeTo = Instant.parse("2026-01-02T00:00:00Z"),
        )
        val ordersBefore = statsRow("team-a", "orders", "1.0.0", started = 1)
        val ordersAfter = statsRow("team-a", "orders", "1.0.0", started = 2)
        val paymentsBefore = statsRow("team-b", "payments", "2.0.0", started = 5)
        val paymentsAfter = statsRow("team-b", "payments", "2.0.0", started = 6)

        coEvery { instanceQueryService.latestLifecycleSequence() } returns 11L
        coEvery {
            definitionStatsQueryService.queryDefinitionStatsSnapshot(filter, 10L)
        } returns listOf(ordersBefore, paymentsBefore)
        coEvery {
            definitionStatsQueryService.queryDefinitionStatsSnapshot(filter, 11L)
        } returns listOf(ordersAfter, paymentsAfter)

        // The client receives the first change of the poll, then disconnects.
        var firstChange: DefinitionStatsChangeRow? = null
        service.watch(filter, afterSequence = 10L) { change ->
            firstChange = change
            false
        }
        firstChange?.stats shouldBe ordersAfter

        // It resumes from the cursor of the last change it received. The timeout (virtual time)
        // fails fast if the resumed watch has nothing left to emit.
        val resumed = mutableListOf<DefinitionStatsChangeRow>()
        withTimeout(1_000) {
            service.watch(filter, afterSequence = firstChange!!.cursor) { change ->
                resumed += change
                resumed.size < 2
            }
        }

        resumed.map { it.stats } shouldContainExactly listOf(ordersAfter, paymentsAfter)
        resumed.last().cursor shouldBe 11L
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
}
