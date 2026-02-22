// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.watch

import com.lemline.common.values.WorkflowId
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventRow
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.config.GatewayConfigConstants
import com.lemline.runner.gateway.outbox.GatewayCommandOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class WorkflowWatchService(
    @ConfigProperty(
        name = GatewayConfigConstants.GATEWAY_WATCH_POLL_INTERVAL_MS,
        defaultValue = GatewayConfigConstants.GATEWAY_WATCH_POLL_INTERVAL_MS_DEFAULT
    )
    private val pollIntervalMs: Long,
    @ConfigProperty(
        name = GatewayConfigConstants.GATEWAY_WATCH_BATCH_SIZE,
        defaultValue = GatewayConfigConstants.GATEWAY_WATCH_BATCH_SIZE_DEFAULT
    )
    private val batchSize: Int,
) {
    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var gatewayOutboxRepository: GatewayCommandOutboxRepository

    @Inject
    lateinit var eventSource: WorkflowAnalyticsEventSource

    suspend fun watch(
        workflowId: WorkflowId,
        principal: GatewayPrincipal,
        onEvent: suspend (WorkflowAnalyticsEventRow) -> Boolean,
    ) {
        authorizer.requireScope(principal, "lemline.watch")

        while (currentCoroutineContext().isActive) {
            val outboxEntry = gatewayOutboxRepository.findByWorkflowId(workflowId)
            if (outboxEntry != null) {
                authorizer.requireNamespace(principal, outboxEntry.workflowNamespace.toString())
                break
            }
            delay(pollIntervalMs)
        }

        var lastSequence = 0L
        while (currentCoroutineContext().isActive) {
            val rows = eventSource.listByWorkflowIdAfter(
                workflowId = workflowId,
                afterSequenceExclusive = lastSequence,
                limit = batchSize
            )

            if (rows.isEmpty()) {
                delay(pollIntervalMs)
                continue
            }

            for (row in rows) {
                val shouldContinue = onEvent(row)
                if (!shouldContinue) return
                lastSequence = row.cursor
            }
        }
    }
}
