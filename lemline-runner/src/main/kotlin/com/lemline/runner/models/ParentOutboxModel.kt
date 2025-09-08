// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("p") // <- type discriminator for polymorphic serialization
data class ParentOutboxModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override val instanceMessage: InstanceMessage,

    @SerialName("s")
    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override var outboxScheduledFor: Instant?
) : OutboxModel() {

    @Transient
    override var outboxDelayedUntil: Instant? = outboxScheduledFor

    @Transient
    override var outboxAttemptCount: Int = 0

    @Transient
    override var outboxErrorClass: String? = null

    @Transient
    override var outboxErrorMessage: String? = null

    @Transient
    override var outboxErrorStackTrace: String? = null

    /**
     * Completes the instance's state by setting the output at the current position.
     * Set also outboxScheduledFor as now to restart this workflow.
     */
    fun completeWith(output: JsonElement) {
        // Update the workflow state with the output
        instanceMessage.workflowState.setCurrentTaskOutput(output)
        // Set to restart the parent workflow via the ParentOutbox
        val now = Clock.System.now()
        outboxScheduledFor = now
        outboxDelayedUntil = now
    }

    override fun toJsonString() = LemlineJson.encodeToString(this)

    // Needed by tests
    companion object
}
