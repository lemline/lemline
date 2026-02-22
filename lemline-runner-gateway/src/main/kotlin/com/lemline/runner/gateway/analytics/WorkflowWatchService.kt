// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_WATCH_BATCH_SIZE
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_WATCH_BATCH_SIZE_DEFAULT
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_WATCH_POLL_INTERVAL_MS
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_WATCH_POLL_INTERVAL_MS_DEFAULT
import com.lemline.runner.gateway.outbox.GatewayCommandOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class WorkflowWatchService(
    @ConfigProperty(name = GATEWAY_WATCH_POLL_INTERVAL_MS, defaultValue = GATEWAY_WATCH_POLL_INTERVAL_MS_DEFAULT)
    private val pollIntervalMs: Long,
    @ConfigProperty(name = GATEWAY_WATCH_BATCH_SIZE, defaultValue = GATEWAY_WATCH_BATCH_SIZE_DEFAULT)
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

        while (coroutineContext.isActive) {
            val outboxEntry = gatewayOutboxRepository.findByWorkflowId(workflowId)
            if (outboxEntry != null) {
                authorizer.requireNamespace(principal, outboxEntry.workflowNamespace.toString())
                break
            }
            delay(pollIntervalMs)
        }

        var lastSequence = 0L
        while (coroutineContext.isActive) {
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
                if (row.eventType in TERMINAL_EVENT_TYPES) return
            }
        }
    }

    companion object {
        private val TERMINAL_EVENT_TYPES = setOf(
            LifecycleEventType.WORKFLOW_COMPLETED.type,
            LifecycleEventType.WORKFLOW_FAULTED.type,
        )
    }
}
