// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.bases.OutboxModel
import com.lemline.runner.outbox.bases.RunStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("w") // <- type discriminator for polymorphic serialization
data class WaitModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override val instanceMessage: InstanceMessage,

    @SerialName("rs")
    override var runStatus: RunStatus = RunStatus.PENDING,

    @SerialName("ra")
    override var runAt: Instant,
) : IngestionModel, OutboxModel {

    @Transient
    override var runDelayedUntil: Instant = runAt

    @Transient
    override var runAttemptCount: Int = 0

    @Transient
    override var runLastErrorClass: String? = null

    @Transient
    override var runLastErrorMessage: String? = null

    @Transient
    override var runLastErrorStackTrace: String? = null

    // Needed by tests
    companion object
}
