// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.values.IDV7
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("f") // <- type discriminator for polymorphic serialization
data class FailureIngestionMessage(
    @SerialName("id")
    val id: IDV7,

    @SerialName("i")
    override val instanceMessage: InstanceMessage?,

    @SerialName("p")
    val payload: String?,

    @SerialName("er")
    val errorReason: String,

    @SerialName("ec")
    val errorClass: String,

    @SerialName("em")
    val errorMessage: String?,

    @SerialName("es")
    val errorStackTrace: String,

    ) : IngestionMessage {
    fun toModel() = FailureModel(
        id = id,
        instanceMessage = instanceMessage,
        payload = payload,
        errorReason = errorReason,
        errorClass = errorClass,
        errorMessage = errorMessage,
        errorStackTrace = errorStackTrace,
    )

    companion object {
        fun from(
            id: IDV7,
            instance: InstanceMessage,
            error: Throwable,
            reason: String = getFailureReason(error)
        ) = FailureIngestionMessage(
            id = IDV7.from(id),
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
        ) = FailureIngestionMessage(
            id = IDV7.from(id),
            instanceMessage = null,
            payload = payload,
            errorReason = reason,
            errorClass = error::class.qualifiedName!!,
            errorMessage = error.message,
            errorStackTrace = error.stackTraceToString()
        )
    }
}
