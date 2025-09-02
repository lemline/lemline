// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.messaging.MessageSubscriberMetrics
import java.util.*
import kotlin.time.ExperimentalTime

@ExperimentalTime
data class FailureModel(
    override val id: UUID = IdGenerator.generateUUIDV7(),
    override var instance: InstanceMessage,
    val message: String? = null,
    val reason: String,
    val errorClass: String,
    val errorMessage: String?,
    val errorStackTrace: String,
) : WithInstance {
    companion object {
        fun from(
            instance: InstanceMessage,
            error: Throwable,
            reason: String = MessageSubscriberMetrics.getFailureReason(error)
        ) = FailureModel(
            instance = instance,
            reason = reason,
            errorClass = error::class.qualifiedName!!,
            errorMessage = error.message,
            errorStackTrace = error.stackTraceToString()
        )
    }
}
