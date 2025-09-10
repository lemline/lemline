// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * This class contains the info needed to proceed after a workflow completion
 */
@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("c") // <- type discriminator for polymorphic serialization
data class CompletedMessage(
    override val workflowId: WorkflowId,
    override val workflowName: WorkflowName,
    override val workflowVersion: WorkflowVersion,
    val parentId: IDV7?,
    val output: JsonElement?,
    val isScheduledAfter: Boolean
) : DatabaseMessage {
    init {
        require((parentId == null) == (output == null)) { "Output must be defined if parentId is not null" }
    }
}
