// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.runner.failures.FailureReasons
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Transient

@ExperimentalTime
data class FailureModel(
    override val id: IDV7,
    var instanceMessage: InstanceMessage?,
    val payload: String?,
    val errorReason: String,
    val errorClass: String,
    val errorMessage: String?,
    val errorStackTrace: String,
) : WithInstance {

    @Transient
    override val workflowState get() = instanceMessage?.workflowState

    @Transient
    override val parentId get() = instanceMessage?.parentId

    override fun toJsonString() = LemlineJson.encodeToString(this)

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
            errorReason = reason,
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
            errorReason = reason,
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
                errorReason = FailureReasons.OUTBOX_FAILURE,
                errorClass = outbox.outboxErrorClass!!,
                errorMessage = outbox.outboxErrorMessage,
                errorStackTrace = outbox.outboxErrorStackTrace!!
            )
        }
    }
}
