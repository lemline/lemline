// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instances

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.gateway.auth.GatewayPrincipal
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InstanceRpcHandlerStartTest {
    @Test
    fun `schedule-only response exposes the registration and scheduled time`() = runTest {
        val request = StartInstanceRequest.getDefaultInstance()
        val principal = mockk<GatewayPrincipal>()
        val startService = mockk<InstanceStartService>()
        val scheduledAt = Instant.parse("2026-11-13T16:11:00Z")
        coEvery { startService.start(request, principal) } returns InstanceStartResult(
            instanceId = WorkflowId(IDV7.from("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")),
            namespace = WorkflowNamespace("team-a"),
            name = WorkflowName("orders"),
            version = WorkflowVersion("1.0.0"),
            result = StartInstanceOutcome.ACCEPTED_NEW,
            scheduleOnly = true,
            nextRunAt = scheduledAt,
        )
        val handler = InstanceRpcHandler(mockk<LemlineConfiguration>()).apply {
            this.startService = startService
        }

        val response = handler.startInstance(request, principal)

        response.scheduleOnly shouldBe true
        response.hasNextRunAt() shouldBe true
        response.nextRunAt.seconds shouldBe scheduledAt.epochSeconds
    }
}
