// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instanceevents

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayWatchBatchSize
import com.lemline.runner.config.gatewayWatchPollIntervalMs
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@ApplicationScoped
class InstanceEventWatchService(
    private val config: LemlineConfiguration,
) {
    private val pollIntervalMs: Long get() = config.gatewayWatchPollIntervalMs
    private val batchSize: Int get() = config.gatewayWatchBatchSize

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var instanceReadAccessService: InstanceReadAccessService

    @Inject
    lateinit var instanceEventQueryService: InstanceEventQueryService

    suspend fun watch(
        instanceId: UUID,
        principal: GatewayPrincipal,
        afterCursorExclusive: Long? = null,
        onEvent: suspend (InstanceEventRow) -> Boolean,
    ) {
        authorizer.requireScope(principal, "lemline.watch")
        instanceReadAccessService.authorizeRead(principal, instanceId)

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
}
