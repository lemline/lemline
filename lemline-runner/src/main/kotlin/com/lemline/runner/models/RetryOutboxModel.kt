// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.errors.WorkflowException
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
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
data class RetryOutboxModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    override val instanceMessage: InstanceMessage,

    @SerialName("s")
    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    @SerialName("f")
    override var outboxScheduledFor: Instant?,

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

) : OutboxModel() {

    init {
        require(outboxScheduledFor != null) { "outboxScheduledFor cannot be null" }
    }

    @Transient
    override var outboxDelayedUntil: Instant? = outboxScheduledFor
        set(until) {
            require(until != null) { "outboxDelayedUntil cannot be null" }
            field = until
        }

    @Transient
    override var outboxAttemptCount: Int = 0

    @Transient
    override var outboxErrorClass: String? = null

    @Transient
    override var outboxErrorMessage: String? = null

    @Transient
    override var outboxErrorStackTrace: String? = null

    companion object {
        fun from(
            id: IDV7 = IDV7.random(),
            instance: InstanceMessage,
            outboxScheduledFor: Instant,
            error: Throwable,
            reason: String
        ) = RetryOutboxModel(
            id = id,
            instanceMessage = instance,
            errorReason = reason,
            errorClass = when (error) {
                is InternalWorkflowException -> error.error.type
                is WorkflowException -> error::class.simpleName ?: "WorkflowException"
                else -> error::class.qualifiedName!!
            },
            errorMessage = when (error) {
                is InternalWorkflowException -> error.error.title
                else -> error.message
            },
            errorStackTrace = when (error) {
                is InternalWorkflowException -> error.error.toJsonPrettyString()
                else -> error.stackTraceToString()
            },
            outboxScheduledFor = outboxScheduledFor,
        )
    }
}
