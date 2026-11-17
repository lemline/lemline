// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.common.values.NodePosition
import com.lemline.core.tasks.FlowDirective
import kotlin.time.Instant

data class PendingTaskState(override val startedAt: Instant) : NodeState()

enum class ExtensionPhase { BEFORE, BODY, AFTER, READY }

data class ExtensionExecutionState(
    val selectedExtensionIndices: List<Int>,
    val phase: ExtensionPhase,
    val activeExtensionIndex: Int?,
    val rawInput: JsonNode,
    val transformedInput: JsonNode,
    val preparedResult: PreparedTaskResult? = null,
) {
    fun validate(state: NodeState) {
        require(selectedExtensionIndices.distinct().sorted() == selectedExtensionIndices && selectedExtensionIndices.all { it >= 0 }) {
            "Selected extension indices must be nonnegative, distinct, and in declaration order"
        }
        when (phase) {
            ExtensionPhase.BEFORE -> require(state is PendingTaskState && preparedResult == null && activeExtensionIndex in selectedExtensionIndices)
            ExtensionPhase.BODY -> require(state !is PendingTaskState && preparedResult == null && activeExtensionIndex == null)
            ExtensionPhase.AFTER -> require(state !is PendingTaskState && preparedResult != null && activeExtensionIndex in selectedExtensionIndices)
            ExtensionPhase.READY -> require(preparedResult != null && activeExtensionIndex == null)
        }
    }
}

data class PreparedTaskResult(
    val rawOutput: JsonNode,
    val transformedOutput: JsonNode,
    val pendingFlowDirective: FlowDirective?,
)

enum class ExtensionReturnKind { COMPLETE, EXIT, END }

data class ExtensionReturn(val blockPosition: NodePosition, val kind: ExtensionReturnKind)
