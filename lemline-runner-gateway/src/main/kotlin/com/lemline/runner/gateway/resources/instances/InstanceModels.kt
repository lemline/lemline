// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instances

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

enum class InstanceChangeKind {
    UPSERT,
    REMOVED,
}

data class InstanceChangeRow(
    val cursor: Long,
    val changeType: InstanceChangeKind,
    val instance: InstanceSummaryRow,
)

data class WorkflowEventSequence(
    val sequence: Long,
    val workflowId: UUID,
)
