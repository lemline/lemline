// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.errors.WorkflowException
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
@SerialName("r") // <- type discriminator for polymorphic serialization
data class RetryModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override val instanceMessage: InstanceMessage,

    @SerialName("rs")
    override var runStatus: RunStatus = RunStatus.PENDING,

    @SerialName("ra")
    override var runAt: Instant,

    /**
     * Reason for this retry
     */
    @SerialName("er")
    val errorReason: String,

    /**
     * Error class of the exception that triggered this retry
     */
    @SerialName("ec")
    val errorClass: String,

    /**
     * Error message of the exception that triggered this retry
     */
    @SerialName("em")
    val errorMessage: String?,

    /**
     * Stacktrace of the exception that triggered this retry
     */
    @SerialName("es")
    val errorStackTrace: String

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

    companion object {
        fun from(
            id: IDV7,
            instance: InstanceMessage,
            outboxScheduledFor: Instant,
            error: Throwable,
            reason: String
        ) = RetryModel(
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
            runAt = outboxScheduledFor,
        )
    }
}
