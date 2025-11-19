// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowException
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
data class RetryOutboxModel(
    override val id: IDV7,
    override val instanceMessage: InstanceMessage<WorkflowEvent.RetryScheduled>,
    val scheduledFor: Instant,

    /**
     * Reason for this retry
     */
    val errorReason: String,

    /**
     * Error class of the exception that triggered this retry
     */
    val errorClass: String,

    /**
     * Error message of the exception that triggered this retry
     */
    val errorMessage: String?,

    /**
     * Stacktrace of the exception that triggered this retry
     */
    val errorStackTrace: String,

    override var outboxCompletedAt: Instant? = null

) : OutboxModel() {

    override var outboxScheduledFor: Instant? = scheduledFor

    override var outboxDelayedUntil: Instant? = scheduledFor
        set(until) {
            require(until != null) { "outboxDelayedUntil cannot be null for RetryOutboxModel" }
            field = until
        }

    override var outboxAttemptCount: Int = 0

    override var outboxFailedAt: Instant? = null

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    companion object {
        fun from(
            id: IDV7 = IDV7.random(),
            instance: InstanceMessage<WorkflowEvent.RetryScheduled>,
            scheduledFor: Instant,
            error: Throwable,
            reason: String
        ) = RetryOutboxModel(
            id = id,
            instanceMessage = instance,
            scheduledFor = scheduledFor,
            errorReason = reason,
            errorClass = when (error) {
                is InternalException -> error.error.type
                is WorkflowException -> error::class.simpleName ?: "WorkflowException"
                else -> error::class.qualifiedName!!
            },
            errorMessage = when (error) {
                is InternalException -> error.error.title
                else -> error.message
            },
            errorStackTrace = when (error) {
                is InternalException -> error.error.toJsonPrettyString()
                else -> error.stackTraceToString()
            },
        )
    }
}
