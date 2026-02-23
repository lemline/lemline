// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayWatchBatchSize
import com.lemline.runner.config.gatewayWatchPollIntervalMs
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@ApplicationScoped
class InstanceStreamService(
    private val config: LemlineConfiguration,
) {
    private val pollIntervalMs: Long get() = config.gatewayWatchPollIntervalMs
    private val batchSize: Int get() = config.gatewayWatchBatchSize

    @Inject
    lateinit var statsQueryService: StatsQueryService

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    suspend fun watchDefinitionStats(
        filter: StatsQueryFilter,
        onUpdate: suspend (DefinitionStatsRow) -> Boolean,
    ) {
        var previous = emptyMap<StatsKey, DefinitionStatsRow>()

        while (currentCoroutineContext().isActive) {
            val currentList = statsQueryService.queryStats(filter)
            val current = currentList.associateBy { StatsKey(it.namespace, it.name, it.version) }

            val updates = buildList {
                current.forEach { (key, value) ->
                    val old = previous[key]
                    if (old != value) add(value)
                }

                previous.keys.minus(current.keys).forEach { removedKey ->
                    add(
                        DefinitionStatsRow(
                            namespace = removedKey.namespace,
                            name = removedKey.name,
                            version = removedKey.version,
                            started = 0,
                            completed = 0,
                            faulted = 0,
                            running = 0,
                        )
                    )
                }
            }

            for (update in updates.sortedWith(compareBy({ it.namespace }, { it.name }, { it.version ?: "" }))) {
                val shouldContinue = onUpdate(update)
                if (!shouldContinue) return
            }

            previous = current
            delay(pollIntervalMs)
        }
    }

    suspend fun watchInstances(
        filter: WatchInstancesFilter,
        afterSequence: Long?,
        onUpdate: suspend (InstanceStreamUpdate) -> Boolean,
    ) {
        var lastSequence = afterSequence ?: instanceQueryService.latestSequence()

        while (currentCoroutineContext().isActive) {
            val rows = instanceQueryService.listWorkflowEventsAfter(
                afterSequenceExclusive = lastSequence,
                limit = batchSize,
                filter = filter,
            )

            if (rows.isEmpty()) {
                delay(pollIntervalMs)
                continue
            }

            for (row in rows) {
                lastSequence = row.sequence
                val instance = instanceQueryService.findInstance(row.workflowId) ?: continue
                val updateType = if (instanceQueryService.isFirstWorkflowEvent(row.workflowId, row.sequence)) {
                    InstanceUpdateType.CREATED
                } else {
                    InstanceUpdateType.UPDATED
                }

                val shouldContinue = onUpdate(
                    InstanceStreamUpdate(
                        instance = instance,
                        updateType = updateType,
                        sequence = row.sequence,
                    )
                )
                if (!shouldContinue) return
            }
        }
    }

    private data class StatsKey(
        val namespace: String,
        val name: String,
        val version: String?,
    )
}
