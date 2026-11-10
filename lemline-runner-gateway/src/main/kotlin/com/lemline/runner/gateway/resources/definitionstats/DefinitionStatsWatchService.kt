// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitionstats

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayWatchPollIntervalMs
import com.lemline.runner.gateway.resources.instances.InstanceQueryService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@ApplicationScoped
class DefinitionStatsWatchService(
    private val config: LemlineConfiguration,
) {
    private val pollIntervalMs: Long get() = config.gatewayWatchPollIntervalMs

    @Inject
    lateinit var definitionStatsQueryService: DefinitionStatsQueryService

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    suspend fun watch(
        filter: DefinitionStatsFilter,
        afterSequence: Long?,
        onUpdate: suspend (DefinitionStatsChangeRow) -> Boolean,
    ) {
        var lastSequence = afterSequence ?: instanceQueryService.latestLifecycleSequence()
        var previous = definitionStatsQueryService.queryDefinitionStatsSnapshot(filter, lastSequence)
            .associateBy { StatsKey(it.namespace, it.name, it.version) }

        while (currentCoroutineContext().isActive) {
            val currentSequence = instanceQueryService.latestLifecycleSequence()
            if (currentSequence <= lastSequence) {
                delay(pollIntervalMs)
                continue
            }

            val current = definitionStatsQueryService.queryDefinitionStatsSnapshot(filter, currentSequence)
                .associateBy { StatsKey(it.namespace, it.name, it.version) }

            val updates = buildList {
                current.forEach { (key, value) ->
                    if (previous[key] != value) {
                        add(
                            DefinitionStatsChangeRow(
                                cursor = currentSequence,
                                changeType = DefinitionStatsChangeKind.UPSERT,
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
                                changeType = DefinitionStatsChangeKind.REMOVED,
                                stats = value,
                            )
                        )
                    }
                }
            }.sortedWith(
                compareBy<DefinitionStatsChangeRow>({ it.stats.namespace }, { it.stats.name }, { it.stats.version })
                    .thenBy { it.changeType.name }
            ).let { sorted ->
                // Only the last change of a poll advances the cursor: a client resuming from an
                // earlier change replays the whole diff from `lastSequence` instead of skipping
                // the rest of it. Rows carry full state, so redelivered changes are idempotent.
                sorted.mapIndexed { index, change ->
                    if (index == sorted.lastIndex) change else change.copy(cursor = lastSequence)
                }
            }

            for (update in updates) {
                if (!onUpdate(update)) return
            }

            previous = current
            lastSequence = currentSequence
        }
    }

    private data class StatsKey(
        val namespace: String,
        val name: String,
        val version: String,
    )
}
