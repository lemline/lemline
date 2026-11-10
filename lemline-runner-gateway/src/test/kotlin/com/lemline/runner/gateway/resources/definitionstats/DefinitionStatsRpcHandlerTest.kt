// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitionstats

import com.google.protobuf.Timestamp
import com.lemline.gateway.v1.ListDefinitionStatsRequest
import com.lemline.gateway.v1.WatchDefinitionStatsRequest
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.resources.instances.InstanceQueryService
import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefinitionStatsRpcHandler")
class DefinitionStatsRpcHandlerTest {

    private val queryService = mockk<DefinitionStatsQueryService>()
    private val watchService = mockk<DefinitionStatsWatchService>()
    private val instanceQueryService = mockk<InstanceQueryService>()

    private val handler = DefinitionStatsRpcHandler(testLemlineConfiguration()).apply {
        authorizer = mockk<GatewayAuthorizer>(relaxed = true)
        definitionStatsQueryService = queryService
        definitionStatsWatchService = watchService
        instanceQueryService = this@DefinitionStatsRpcHandlerTest.instanceQueryService
    }

    private val principal = GatewayPrincipal(subject = "test", scopes = setOf("*"), namespaces = setOf("*"))
    private val timeFrom = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `list without time_to opens the window and its cursor resumes a watch without time_to`() = runTest {
        val listedFilter = slot<DefinitionStatsFilter>()
        coEvery { instanceQueryService.latestLifecycleSequence() } returns 42L
        coEvery { queryService.listDefinitionStats(capture(listedFilter), 42L, null) } returns
            DefinitionStatsListResult(stats = emptyList(), nextPage = null, totalCount = 0L, watchSequence = 42L)
        val watchedFilter = slot<DefinitionStatsFilter>()
        coEvery { watchService.watch(capture(watchedFilter), any(), any()) } returns Unit

        val listed = handler.listDefinitionStats(
            ListDefinitionStatsRequest.newBuilder().setTimeFrom(timestamp(timeFrom)).build(),
            principal,
        )
        handler.watchDefinitionStats(
            WatchDefinitionStatsRequest.newBuilder()
                .setTimeFrom(timestamp(timeFrom))
                .setAfterCursor(listed.watchCursor)
                .build(),
            principal,
        ) { true }

        listedFilter.captured.timeTo shouldBe null
        watchedFilter.captured.timeTo shouldBe null
        coVerify { watchService.watch(any(), 42L, any()) }
    }

    private fun timestamp(instant: Instant): Timestamp =
        Timestamp.newBuilder().setSeconds(instant.epochSecond).setNanos(instant.nano).build()
}
