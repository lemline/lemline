// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.failures

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowState
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithId
import com.lemline.runner.failures.FailureReasons.getFailureReason

data class FailureModel(
    /** Unique identifier for this failure record */
    override val id: IDV7,

    /** Workflow instance state when the failure occurred, null if payload deserialization failed */
    val instanceMessage: InstanceMessage<out WorkflowState>?,

    /** Raw message payload if instanceMessage deserialization failed */
    val payload: String?,

    /** High-level categorization of the failure reason */
    val errorReason: String,

    /** Fully qualified class name of the exception that caused the failure */
    val errorClass: String,

    /** Error message from the exception */
    val errorMessage: String?,

    /** Full stack trace of the exception for debugging */
    val errorStackTrace: String,
) : WithId {

    companion object {
        /**
         * Creates a FailureModel from a workflow instance.
         *
         * @param id Must be derived from position + step for idempotency
         */
        fun from(
            id: IDV7,
            instance: InstanceMessage<out WorkflowState>,
            exception: Exception,
            reason: String = getFailureReason(exception)
        ) = FailureModel(
            id = id,
            instanceMessage = instance,
            payload = null,
            errorReason = reason,
            errorClass = exception::class.qualifiedName!!,
            errorMessage = exception.message,
            errorStackTrace = exception.stackTraceToString()
        )

        /**
         * Creates a FailureModel from a raw payload (deserialization failure).
         * Uses random ID since we don't have a valid instance to derive from.
         */
        fun from(
            id: IDV7 = IDV7.random(),
            payload: String,
            exception: Exception,
            reason: String = getFailureReason(exception)
        ) = FailureModel(
            id = id,
            instanceMessage = null,
            payload = payload,
            errorReason = reason,
            errorClass = exception::class.qualifiedName!!,
            errorMessage = exception.message,
            errorStackTrace = exception.stackTraceToString()
        )
    }
}
