// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instances

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayWatchBatchSize
import com.lemline.runner.config.gatewayWatchPollIntervalMs
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@ApplicationScoped
class InstanceWatchService(
    private val config: LemlineConfiguration,
) {
    private val pollIntervalMs: Long get() = config.gatewayWatchPollIntervalMs
    private val batchSize: Int get() = config.gatewayWatchBatchSize

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    suspend fun watch(
        filter: InstanceListFilter,
        afterSequence: Long?,
        onUpdate: suspend (InstanceChangeRow) -> Boolean,
    ) {
        var lastSequence = afterSequence ?: instanceQueryService.latestLifecycleSequence()

        while (currentCoroutineContext().isActive) {
            val rows = instanceQueryService.listChangedInstanceSequencesAfter(
                afterSequenceExclusive = lastSequence,
                limit = batchSize,
                filter = filter,
            )

            if (rows.isEmpty()) {
                delay(pollIntervalMs)
                continue
            }

            for (row in rows) {
                val before = if (row.sequence > 0) {
                    instanceQueryService.findInstance(row.workflowId, row.sequence - 1)
                } else {
                    null
                }
                val after = instanceQueryService.findInstance(row.workflowId, row.sequence)

                val beforeInstance = before?.instance
                val afterInstance = after?.instance
                val beforeMatches = beforeInstance?.let { instanceQueryService.matchesFilter(it, filter) } == true
                val afterMatches = afterInstance?.let { instanceQueryService.matchesFilter(it, filter) } == true

                val change = when {
                    afterMatches -> InstanceChangeRow(
                        cursor = row.sequence,
                        changeType = InstanceChangeKind.UPSERT,
                        instance = afterInstance!!,
                    )

                    beforeMatches -> InstanceChangeRow(
                        cursor = row.sequence,
                        changeType = InstanceChangeKind.REMOVED,
                        instance = afterInstance ?: beforeInstance!!,
                    )

                    else -> null
                }

                if (change != null && !onUpdate(change)) return
                lastSequence = row.sequence
            }
        }
    }
}
