// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.start

import com.lemline.common.values.WorkflowId
import kotlin.time.Instant

data class GatewayWorkflowReservation(
    val workflowId: WorkflowId,
    val workflowNamespace: String,
    val workflowName: String,
    val workflowVersion: String,
    val inputJson: String,
    val zoneId: String,
    val requestFingerprint: String,
    val startedAt: Instant,
)

enum class ReserveResultType {
    NEW,
    EXISTING_SAME,
    EXISTING_MISMATCH,
}

data class ReserveResult(
    val type: ReserveResultType,
    val reservation: GatewayWorkflowReservation,
)
