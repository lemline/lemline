// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.values.IDV7
import com.lemline.core.errors.WorkflowException
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("r") // <- type discriminator for polymorphic serialization
data class RetryIngestionMessage(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override val instanceMessage: InstanceMessage,

    @SerialName("s")
    override val outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override val outboxScheduledFor: Instant?,

    @SerialName("er")
    val errorReason: String,

    @SerialName("ec")
    val errorClass: String,

    @SerialName("em")
    val errorMessage: String?,

    @SerialName("es")
    val errorStackTrace: String,
) : OutboxIngestionMessage, IngestionMessage {
    fun toModel() = RetryOutboxModel(
        id = id,
        instanceMessage = instanceMessage,
        outBoxStatus = outBoxStatus,
        outboxScheduledFor = outboxScheduledFor,
        errorReason = errorReason,
        errorClass = errorClass,
        errorMessage = errorMessage,
        errorStackTrace = errorStackTrace,
    )

    companion object {
        fun from(
            id: IDV7,
            instance: InstanceMessage,
            outboxScheduledFor: Instant,
            error: Throwable,
            reason: String
        ) = RetryIngestionMessage(
            id = id,
            instanceMessage = instance,
            errorReason = reason,
            errorClass = when (error) {
                is WorkflowException -> error.error.type
                else -> error::class.qualifiedName!!
            },
            errorMessage = when (error) {
                is WorkflowException -> error.error.title
                else -> error.message
            },
            errorStackTrace = when (error) {
                is WorkflowException -> error.error.toJsonPrettyString()
                else -> error.stackTraceToString()
            },
            outboxScheduledFor = outboxScheduledFor,
        )
    }
}
