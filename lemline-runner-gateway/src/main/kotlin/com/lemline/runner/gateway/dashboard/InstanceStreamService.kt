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
        filter: DefinitionStatsFilter,
        afterSequence: Long?,
        onUpdate: suspend (DefinitionStatsChangeRow) -> Boolean,
    ) {
        var lastSequence = afterSequence ?: instanceQueryService.latestSequence()
        var previous = statsQueryService.queryDefinitionStatsSnapshot(filter, lastSequence)
            .associateBy { StatsKey(it.namespace, it.name, it.version) }

        while (currentCoroutineContext().isActive) {
            val currentSequence = instanceQueryService.latestSequence()
            if (currentSequence <= lastSequence) {
                delay(pollIntervalMs)
                continue
            }

            val current = statsQueryService.queryDefinitionStatsSnapshot(filter, currentSequence)
                .associateBy { StatsKey(it.namespace, it.name, it.version) }

            val updates = buildList {
                current.forEach { (key, value) ->
                    if (previous[key] != value) {
                        add(
                            DefinitionStatsChangeRow(
                                cursor = currentSequence,
                                changeType = ChangeType.UPSERT,
                                stats = value,
                            )
                        )
                    }
                }
                previous.forEach { (key, value) ->
                    if (key !in current) {
                        add(
                            DefinitionStatsChangeRow(
                                cursor = currentSequence,
                                changeType = ChangeType.REMOVED,
                                stats = value,
                            )
                        )
                    }
                }
            }.sortedWith(
                compareBy<DefinitionStatsChangeRow>({ it.stats.namespace }, { it.stats.name }, { it.stats.version })
                    .thenBy { it.changeType.name }
            )

            for (update in updates) {
                if (!onUpdate(update)) return
            }

            previous = current
            lastSequence = currentSequence
        }
    }

    suspend fun watchInstances(
        filter: InstanceListFilter,
        afterSequence: Long?,
        onUpdate: suspend (InstanceChangeRow) -> Boolean,
    ) {
        var lastSequence = afterSequence ?: instanceQueryService.latestSequence()

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
                        changeType = ChangeType.UPSERT,
                        instance = afterInstance!!,
                    )

                    beforeMatches -> InstanceChangeRow(
                        cursor = row.sequence,
                        changeType = ChangeType.REMOVED,
                        instance = afterInstance ?: beforeInstance!!,
                    )

                    else -> null
                }

                if (change != null && !onUpdate(change)) return
                lastSequence = row.sequence
            }
        }
    }

    private data class StatsKey(
        val namespace: String,
        val name: String,
        val version: String,
    )
}
