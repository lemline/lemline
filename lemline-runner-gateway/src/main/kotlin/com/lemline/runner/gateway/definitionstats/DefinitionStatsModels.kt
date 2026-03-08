// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.definitionstats

import java.time.Instant

data class DefinitionStatsFilter(
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
    val allowedNamespaces: Set<String>? = null,
    val timeFrom: Instant,
    val timeTo: Instant,
    val pageSize: Int = 50,
)

data class DefinitionStatsRow(
    val namespace: String,
    val name: String,
    val version: String,
    val startedCount: Long,
    val completedCount: Long,
    val faultedCount: Long,
    val runningCountAtTimeTo: Long,
)

data class DefinitionStatsPageBoundary(
    val namespace: String,
    val name: String,
    val version: String,
)

data class DefinitionStatsListResult(
    val stats: List<DefinitionStatsRow>,
    val nextPage: DefinitionStatsPageBoundary?,
    val totalCount: Long,
    val watchSequence: Long,
)

enum class DefinitionStatsChangeKind {
    UPSERT,
    REMOVED,
}

data class DefinitionStatsChangeRow(
    val cursor: Long,
    val changeType: DefinitionStatsChangeKind,
    val stats: DefinitionStatsRow,
)
