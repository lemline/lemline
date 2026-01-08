// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

import com.lemline.common.values.IDV7
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowException
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithInstanceMessage
import com.lemline.runner.common.models.WithOutbox
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
data class RetryModel(
    /** Unique identifier for this retry attempt */
    override val id: IDV7,

    /** Workflow state when the retry was scheduled */
    override val instanceMessage: InstanceMessage<WorkflowEvent.TaskRetryScheduled>,

    /** Timestamp when the retry should be attempted */
    override val outboxScheduledFor: Instant,

    /** Reason for this retry */
    val errorReason: String,

    /** Error class of the exception that triggered this retry */
    val errorClass: String,

    /** Error message of the exception that triggered this retry */
    val errorMessage: String?,

    /** Stacktrace of the exception that triggered this retry */
    val errorStackTrace: String,

    ) : WithId, WithInstanceMessage, WithOutbox, WithCleanup {

    override var outboxDelayedUntil: Instant? = outboxScheduledFor
        set(until) {
            require(until != null) { "outboxDelayedUntil cannot be null for RetryOutboxModel" }
            field = until
        }

    override var outboxAttemptCount: Int = 0

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    override var outboxCompletedAt: Instant? = null

    override var outboxFailedAt: Instant? = null

    override var cleanupAfter: Instant? = null

    companion object Companion {
        /**
         * Creates a RetryModel from a retry scheduled event.
         *
         * @param id Must be derived from position + step for idempotency
         */
        fun from(
            id: IDV7,
            instance: InstanceMessage<WorkflowEvent.TaskRetryScheduled>,
            scheduledFor: Instant,
            error: Throwable,
            reason: String
        ) = RetryModel(
            id = id,
            instanceMessage = instance,
            outboxScheduledFor = scheduledFor,
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
