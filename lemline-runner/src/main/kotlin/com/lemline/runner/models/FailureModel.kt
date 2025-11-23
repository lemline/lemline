// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.states.WorkflowState
import com.lemline.runner.failures.FailureReasons
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
data class FailureModel(
    /** Unique identifier for this failure record */
    override val id: IDV7,

    /** Workflow instance state when the failure occurred, null if payload deserialization failed */
    var instanceMessage: InstanceMessage<out WorkflowState>?,

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
) : InstanceModel {

    /** Workflow definition info extracted from the instance message, null if payload deserialization failed */
    override val workflowInfo: WorkflowInfo? get() = instanceMessage?.workflowInfo

    /** Workflow execution state extracted from the instance message, null if payload deserialization failed */
    override val workflowState: WorkflowState? get() = instanceMessage?.workflowState

    companion object {
        fun from(
            id: IDV7 = IDV7.random(),
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

        fun from(outbox: OutboxModel): FailureModel {
            require(outbox.outboxFailedAt != null) { "The outbox must have FAILED" }

            return FailureModel(
                id = IDV7.from(outbox.id),
                instanceMessage = outbox.instanceMessage,
                payload = null,
                errorReason = FailureReasons.OUTBOX_FAILURE,
                errorClass = outbox.outboxErrorClass!!,
                errorMessage = outbox.outboxErrorMessage,
                errorStackTrace = outbox.outboxErrorStackTrace!!
            )
        }
    }
}
