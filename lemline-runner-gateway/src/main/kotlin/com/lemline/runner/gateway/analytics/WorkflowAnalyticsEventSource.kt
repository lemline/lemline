// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.common.values.WorkflowId

data class WorkflowAnalyticsEventRow(
    val sequence: Long,
    val eventType: String,
    val cloudEventJson: String,
)

interface WorkflowAnalyticsEventSource {
    suspend fun listByWorkflowIdAfter(
        workflowId: WorkflowId,
        afterSequenceExclusive: Long,
        limit: Int,
    ): List<WorkflowAnalyticsEventRow>

    suspend fun validate()
}
