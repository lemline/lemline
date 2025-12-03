// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.ListenStrategy
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlin.time.ExperimentalTime

/**
 * Represents a listen task definition extracted from a workflow definition.
 *
 * When a workflow definition containing listen tasks is registered, the listen
 * task configurations are extracted and stored for efficient event routing.
 * This allows the system to quickly identify which listen tasks might be
 * interested in an incoming CloudEvent.
 *
 * One row is created per listen task in the workflow. The associated filters
 * are stored separately in [DefinitionListenFilterModel].
 *
 * @property id Unique identifier for this listen task definition
 * @property workflowNamespace Namespace of the workflow definition
 * @property workflowName Name of the workflow definition
 * @property workflowVersion Version of the workflow definition
 * @property nodePosition Position of the listen task in the workflow tree
 * @property strategy Event consumption strategy (ONE, ANY, ALL)
 * @property readAs How to read event data (DATA, ENVELOPE, RAW)
 */
@ExperimentalTime
data class DefinitionListenModel(
    override val id: IDV7,
    val workflowNamespace: WorkflowNamespace,
    val workflowName: WorkflowName,
    val workflowVersion: WorkflowVersion,
    val nodePosition: NodePosition,
    val strategy: ListenStrategy,
    val readAs: ListenAndReadAs
) : WithId {
    companion object
}
