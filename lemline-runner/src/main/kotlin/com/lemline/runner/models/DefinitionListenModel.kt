// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import kotlin.time.ExperimentalTime

/**
 * Represents a listen filter definition extracted from a workflow definition.
 *
 * When a workflow definition containing listen tasks is registered, the filters
 * are extracted and stored in this table for efficient event routing. This allows
 * incoming CloudEvents to be matched against active listeners without parsing
 * the full workflow definition.
 *
 * One row is created per event filter in each listen task. For ALL strategy with
 * multiple filters, multiple rows are created with different filterIndex values.
 *
 * @property id Unique identifier for this filter definition
 * @property workflowNamespace Namespace of the workflow definition
 * @property workflowName Name of the workflow definition
 * @property workflowVersion Version of the workflow definition
 * @property nodePosition Position of the listen task in the workflow tree
 * @property filterIndex Index of this filter within the listen task (for ALL strategy ordering)
 * @property eventType CloudEvent type to match (exact match)
 * @property eventSource CloudEvent source URI pattern to match
 * @property eventSubject CloudEvent subject to match
 * @property correlations Serialized JSON map of correlation definitions
 */
@ExperimentalTime
data class DefinitionListenModel(
    val id: IDV7,
    val workflowNamespace: WorkflowNamespace,
    val workflowName: WorkflowName,
    val workflowVersion: WorkflowVersion,
    val nodePosition: NodePosition,
    val filterIndex: Int,
    val eventType: String?,
    val eventSource: String?,
    val eventSubject: String?,
    val correlations: String?
) {
    companion object
}
