// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.common.values.IDV7
import com.lemline.common.values.WithDefiniteWorkflowInfo
import com.lemline.common.values.WorkflowInfo
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
    @SerialName("i")
    override val workflowInfo: WorkflowInfo,
    @SerialName("p")
    val parentId: IDV7?,
    @SerialName("o")
    val output: JsonElement?,
    @SerialName("sa")
    val isScheduledAfter: Boolean
) : DatabaseMessage, WithDefiniteWorkflowInfo {
    init {
        require((parentId == null) == (output == null)) { "Output must be defined if parentId is not null" }
    }
}
