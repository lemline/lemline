// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.bases.OptionalCleanerModel
import com.lemline.runner.models.bases.WithInstanceMessage
import com.lemline.runner.outbox.bases.RunStatus
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("p") // <- type discriminator for polymorphic serialization
data class ParentModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override val instanceMessage: InstanceMessage,

    @SerialName("rs")
    override var runStatus: RunStatus = RunStatus.PENDING,

    @SerialName("ra")
    override var runAt: Instant?

) : IngestionModel, WithInstanceMessage, OptionalCleanerModel {

    /**
     * Completes the instance's state by setting the output at the current position.
     * Set also outboxScheduledFor as now to restart this workflow.
     */
    fun completeWith(output: JsonElement) {
        // Update the workflow state with the output
        instanceMessage.workflowState.setCurrentTaskOutput(output)
        // Update the status and scheduled time as we are restarting this workflow
        runStatus = RunStatus.DONE
        runAt = Clock.System.now()
    }

    // Needed by tests
    companion object
}
