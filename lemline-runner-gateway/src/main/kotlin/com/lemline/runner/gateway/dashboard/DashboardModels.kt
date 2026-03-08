// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import java.time.Instant
import java.util.UUID

enum class InstanceStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAULTED;

    companion object {
        fun fromRaw(raw: String?): InstanceStatus =
            when (raw) {
                "PENDING" -> PENDING
                "RUNNING" -> RUNNING
                "COMPLETED" -> COMPLETED
                "FAULTED" -> FAULTED
                else -> error("Unsupported instance status '$raw'")
            }
    }
}

data class DefinitionListFilter(
    val namespace: String? = null,
    val name: String? = null,
    val latestOnly: Boolean = true,
    val allowedNamespaces: Set<String>? = null,
    val pageSize: Int = 50,
)

data class DefinitionSummaryRow(
    val namespace: String,
    val name: String,
    val version: String,
    val isLatest: Boolean,
    val versionCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

data class DefinitionListResult(
    val definitions: List<DefinitionSummaryRow>,
    val nextPage: DefinitionPageBoundary?,
    val totalCount: Long,
)

data class DefinitionDetailsRow(
    val namespace: String,
    val name: String,
    val version: String,
    val source: String,
    val availableVersions: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

data class DefinitionPageBoundary(
    val namespace: String,
    val name: String,
    val version: String,
)

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

data class DefinitionStatsListResult(
    val stats: List<DefinitionStatsRow>,
    val nextPage: DefinitionPageBoundary?,
    val totalCount: Long,
    val watchSequence: Long,
)

data class InstanceListFilter(
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
    val status: InstanceStatus? = null,
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
    val instanceId: UUID? = null,
    val instanceIdPrefix: String? = null,
    val allowedNamespaces: Set<String>? = null,
    val pageSize: Int = 50,
)

data class ErrorSummaryRow(
    val type: String,
    val status: Int?,
    val title: String,
)

data class InstanceSummaryRow(
    val instanceId: UUID,
    val namespace: String,
    val name: String,
    val version: String,
    val status: InstanceStatus,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val faultedAt: Instant?,
    val durationMs: Long?,
    val error: ErrorSummaryRow?,
)

data class InstanceLookupRow(
    val instance: InstanceSummaryRow,
    val latestEventSequence: Long?,
)

data class InstancePageBoundary(
    val createdAt: Instant,
    val instanceId: UUID,
)

data class InstanceListResult(
    val instances: List<InstanceSummaryRow>,
    val nextPage: InstancePageBoundary?,
    val totalCount: Long,
    val watchSequence: Long,
)

enum class ChangeType {
    UPSERT,
    REMOVED,
}

data class DefinitionStatsChangeRow(
    val cursor: Long,
    val changeType: ChangeType,
    val stats: DefinitionStatsRow,
)

data class InstanceChangeRow(
    val cursor: Long,
    val changeType: ChangeType,
    val instance: InstanceSummaryRow,
)

data class InstanceEventFilter(
    val instanceId: UUID,
    val afterSequenceExclusive: Long? = null,
    val pageSize: Int = 50,
)

data class InstanceEventRow(
    val cursor: Long,
    val instanceId: UUID,
    val eventType: String,
    val eventTime: Instant?,
    val cloudEventJson: String,
    val namespace: String?,
)

data class InstanceEventListResult(
    val events: List<InstanceEventRow>,
    val nextCursor: Long?,
    val latestCursor: Long?,
)

data class WorkflowEventSequence(
    val sequence: Long,
    val workflowId: UUID,
)
