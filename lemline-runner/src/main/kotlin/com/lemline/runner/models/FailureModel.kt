// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.JsonSerializable
import com.lemline.common.values.IDV7
import com.lemline.runner.failures.FailureReasons
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.bases.OutboxModelBase
import com.lemline.runner.models.bases.WithOptionalInstanceMessage
import com.lemline.runner.outbox.bases.RunStatus
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("f") // <- type discriminator for polymorphic serialization
data class FailureModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override var instanceMessage: InstanceMessage?,

    @SerialName("p")
    val payload: String?,

    @SerialName("er")
    val errorReason: String,

    @SerialName("ec")
    val errorClass: String,

    @SerialName("em")
    val errorMessage: String?,

    @SerialName("es")
    val errorStackTrace: String

) : IngestionModel, WithOptionalInstanceMessage, JsonSerializable {

    companion object {
        fun from(
            id: IDV7,
            instance: InstanceMessage,
            error: Throwable,
            reason: String = getFailureReason(error)
        ) = FailureModel(
            id = id,
            instanceMessage = instance,
            payload = null,
            errorReason = reason,
            errorClass = error::class.qualifiedName!!,
            errorMessage = error.message,
            errorStackTrace = error.stackTraceToString()
        )

        fun from(
            id: IDV7,
            payload: String,
            error: Throwable,
            reason: String = getFailureReason(error)
        ) = FailureModel(
            id = id,
            instanceMessage = null,
            payload = payload,
            errorReason = reason,
            errorClass = error::class.qualifiedName!!,
            errorMessage = error.message,
            errorStackTrace = error.stackTraceToString()
        )

        fun from(outbox: OutboxModelBase): FailureModel {
            require(outbox.runStatus == RunStatus.FAILED) { "The outbox status must be FAILED" }

            return FailureModel(
                id = outbox.id,
                instanceMessage = outbox.instanceMessage,
                payload = null,
                errorReason = FailureReasons.OUTBOX_FAILURE,
                errorClass = outbox.runLastErrorClass!!,
                errorMessage = outbox.runLastErrorMessage,
                errorStackTrace = outbox.runLastErrorStackTrace!!
            )
        }
    }
}
