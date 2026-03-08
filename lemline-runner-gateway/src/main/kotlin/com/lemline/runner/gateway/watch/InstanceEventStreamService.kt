// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.watch

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayWatchBatchSize
import com.lemline.runner.config.gatewayWatchPollIntervalMs
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.dashboard.InstanceEventFilter
import com.lemline.runner.gateway.dashboard.InstanceEventQueryService
import com.lemline.runner.gateway.dashboard.InstanceEventRow
import com.lemline.runner.gateway.dashboard.InstanceQueryService
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@ApplicationScoped
class InstanceEventStreamService(
    private val config: LemlineConfiguration,
) {
    private val pollIntervalMs: Long get() = config.gatewayWatchPollIntervalMs
    private val batchSize: Int get() = config.gatewayWatchBatchSize

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    @Inject
    lateinit var instanceEventQueryService: InstanceEventQueryService

    suspend fun watch(
        instanceId: UUID,
        principal: GatewayPrincipal,
        afterCursorExclusive: Long? = null,
        onEvent: suspend (InstanceEventRow) -> Boolean,
    ) {
        authorizer.requireScope(principal, "lemline.watch")
        authorizeNamespace(principal, instanceId)

        var lastSequence = afterCursorExclusive
        while (currentCoroutineContext().isActive) {
            val result = instanceEventQueryService.listEvents(
                InstanceEventFilter(
                    instanceId = instanceId,
                    afterSequenceExclusive = lastSequence,
                    pageSize = batchSize,
                )
            )

            if (result.events.isEmpty()) {
                delay(pollIntervalMs)
                continue
            }

            for (event in result.events) {
                if (!onEvent(event)) return
                lastSequence = event.cursor
            }
        }
    }

    private suspend fun authorizeNamespace(principal: GatewayPrincipal, instanceId: UUID) {
        val latestSequence = instanceQueryService.latestSequence()
        val instance = instanceQueryService.findInstance(instanceId, latestSequence)
        if (instance != null) {
            authorizer.requireNamespace(principal, instance.instance.namespace)
            return
        }

        val firstEvent = instanceEventQueryService.firstEvent(instanceId)
            ?: throw GatewayNotFoundException("Instance '$instanceId' not found")
        val namespace = firstEvent.namespace?.takeIf { it.isNotBlank() }
            ?: throw GatewayPermissionDeniedException(
                "Not authorized for instance '$instanceId': namespace is missing from lifecycle event"
            )
        authorizer.requireNamespace(principal, namespace)
    }
}
