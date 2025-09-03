// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import java.util.*
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("f") // <- type discriminator for polymorphic serialization
data class FailureIngestionMessage(
    @SerialName("id")
    override val id: @Contextual UUID,

    @SerialName("i")
    override val instance: InstanceMessage?,

    @SerialName("p")
    val payload: String?,

    @SerialName("r")
    val reason: String,

    @SerialName("ec")
    val errorClass: String,

    @SerialName("em")
    val errorMessage: String?,

    @SerialName("es")
    val errorStackTrace: String,

    ) : IngestionMessage {
    fun toModel() = FailureModel(
        id = id,
        instance = instance,
        payload = payload,
        reason = reason,
        errorClass = errorClass,
        errorMessage = errorMessage,
        errorStackTrace = errorStackTrace,
    )

    companion object {
        fun from(
            id: UUID,
            instance: InstanceMessage,
            error: Throwable,
            reason: String = getFailureReason(error)
        ) = FailureIngestionMessage(
            id = IdGenerator.deriveUuidV7FromV7(id),
            instance = instance,
            payload = null,
            reason = reason,
            errorClass = error::class.qualifiedName!!,
            errorMessage = error.message,
            errorStackTrace = error.stackTraceToString()
        )

        fun from(
            id: UUID,
            payload: String,
            error: Throwable,
            reason: String = getFailureReason(error)
        ) = FailureIngestionMessage(
            id = IdGenerator.deriveUuidV7FromV7(id),
            instance = null,
            payload = payload,
            reason = reason,
            errorClass = error::class.qualifiedName!!,
            errorMessage = error.message,
            errorStackTrace = error.stackTraceToString()
        )
    }
}
