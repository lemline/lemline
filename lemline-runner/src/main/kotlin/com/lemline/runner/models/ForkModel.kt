// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.nodes.NodePosition
import com.lemline.runner.models.bases.OptionalCleanerModel
import com.lemline.runner.models.bases.WithWorkflowInfo
import com.lemline.runner.outbox.bases.RunStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("k") // <- type discriminator for polymorphic serialization
data class ForkModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override val workflowInfo: WorkflowInfo,

    @SerialName("fi")
    val forkId: IDV7,

    @SerialName("fp")
    val forkPosition: NodePosition,

    @SerialName("fn")
    val forkName: String,

    @SerialName("fo")
    val forkOutput: String?,

    @SerialName("rs")
    override var runStatus: RunStatus = RunStatus.PENDING,

    @SerialName("ra")
    override var runAt: Instant?

) : IngestionModel, WithWorkflowInfo, OptionalCleanerModel {
    companion object {
        fun fromJsonString(jsonString: String) = LemlineJson.decodeFromString<ForkModel>(jsonString)
    }
}
