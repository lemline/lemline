// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import java.time.Instant
import java.util.UUID

enum class WorkflowRuntimeStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAULTED,
    UNKNOWN;

    companion object {
        fun fromFilter(raw: String?): WorkflowRuntimeStatus? {
            if (raw == null) return null
            return entries.firstOrNull { it.name == raw.trim().uppercase() }
                ?: throw IllegalArgumentException("Unsupported status '$raw'. Allowed values: ${entries.joinToString { it.name }}")
        }
    }
}

data class DefinitionSummaryRow(
    val namespace: String,
    val name: String,
    val version: String,
    val isLatest: Boolean,
    val versionCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

data class DefinitionDetailsRow(
    val namespace: String,
    val name: String,
    val version: String,
    val definition: String,
    val availableVersions: List<String>,
)

data class StatsQueryFilter(
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
    val timeFrom: Instant? = null,
    val timeTo: Instant? = null,
)

data class DefinitionStatsRow(
    val namespace: String,
    val name: String,
    val version: String?,
    val started: Long,
    val completed: Long,
    val faulted: Long,
    val running: Long,
)

data class InstanceQueryFilter(
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
    val status: WorkflowRuntimeStatus? = null,
    val timeFrom: Instant? = null,
    val timeTo: Instant? = null,
    val workflowId: UUID? = null,
    val workflowIdPrefix: String? = null,
    val pageSize: Int = 50,
    val pageCursor: String? = null,
)

data class ErrorSummaryRow(
    val type: String,
    val status: Int,
    val title: String,
)

data class WorkflowInstanceRow(
    val workflowId: UUID,
    val namespace: String,
    val name: String,
    val version: String,
    val status: WorkflowRuntimeStatus,
    val createdAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val faultedAt: Instant?,
    val durationMs: Long?,
    val error: ErrorSummaryRow?,
)

data class ListInstancesResult(
    val instances: List<WorkflowInstanceRow>,
    val nextCursor: String?,
    val totalCount: Long,
)

data class TimelineEventRow(
    val sequence: Long,
    val eventType: String,
    val eventTime: Instant?,
    val category: String,
    val taskPointer: String?,
    val taskName: String?,
    val payloadJson: String,
)

data class InstanceTimelineRow(
    val workflowId: UUID,
    val namespace: String,
    val name: String,
    val version: String,
    val status: WorkflowRuntimeStatus,
    val events: List<TimelineEventRow>,
    val definition: String?,
)

data class WatchInstancesFilter(
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
)

enum class InstanceUpdateType {
    CREATED,
    UPDATED,
}

data class InstanceStreamUpdate(
    val instance: WorkflowInstanceRow,
    val updateType: InstanceUpdateType,
    val sequence: Long,
)

data class WorkflowEventSequence(
    val sequence: Long,
    val workflowId: UUID,
    val eventType: String,
)
