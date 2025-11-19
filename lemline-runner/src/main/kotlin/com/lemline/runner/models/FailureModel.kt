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
    override val id: IDV7,
    var instanceMessage: InstanceMessage<out WorkflowState>?,
    val payload: String?,
    val errorReason: String,
    val errorClass: String,
    val errorMessage: String?,
    val errorStackTrace: String,
) : InstanceModel {

    override val workflowInfo: WorkflowInfo? get() = instanceMessage?.workflowInfo

    override val workflowState: WorkflowState? get() = instanceMessage?.workflowState

    companion object {
        fun from(
            id: IDV7 = IDV7.random(),
            instance: InstanceMessage<out WorkflowState>,
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
            id: IDV7 = IDV7.random(),
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
