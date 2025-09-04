// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.failures.FailureReasons
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.messaging.LemlineMessage
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.ExperimentalTime

@ExperimentalTime
data class FailureModel(
    override val id: IDV7,
    override var instanceMessage: InstanceMessage?,
    val payload: String?,
    val reason: String,
    val errorClass: String,
    val errorMessage: String?,
    val errorStackTrace: String,
) : LemlineMessage {
    companion object {
        fun from(
            id: IDV7,
            instance: InstanceMessage,
            error: Throwable,
            reason: String = getFailureReason(error)
        ) = FailureModel(
            id = id,
            instanceMessage = instance,
            payload = null,
            reason = reason,
            errorClass = error::class.qualifiedName!!,
            errorMessage = error.message,
            errorStackTrace = error.stackTraceToString()
        )

        fun from(
            id: IDV7,
            payload: String,
            error: Throwable,
            reason: String = getFailureReason(error)
        ) = FailureModel(
            id = id,
            instanceMessage = null,
            payload = payload,
            reason = reason,
            errorClass = error::class.qualifiedName!!,
            errorMessage = error.message,
            errorStackTrace = error.stackTraceToString()
        )

        fun from(outbox: OutboxModel): FailureModel {
            require(outbox.outBoxStatus == OutBoxStatus.FAILED) { "The outbox status must be FAILED" }

            return FailureModel(
                id = IDV7.from(outbox.id),
                instanceMessage = outbox.instanceMessage,
                payload = null,
                reason = FailureReasons.OUTBOX_ERROR,
                errorClass = outbox.outboxErrorClass!!,
                errorMessage = outbox.outboxErrorMessage,
                errorStackTrace = outbox.outboxErrorStackTrace!!
            )
        }
    }
}
