// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.common.json.JsonSerializable
import com.lemline.runner.messaging.instances.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Messages sent to the database channel for persistence.
 *
 * This sealed class represents all types of messages that require database operations,
 * keeping the workflow channel non-blocking even when the database is unavailable.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@Serializable
@JsonClassDiscriminator("t")
sealed class DatabaseMessage : JsonSerializable {

    /**
     * Regular workflow state requiring persistence.
     *
     * Sent when a workflow reaches a pause point (Waiting, Retrying, RunningChildWorkflow)
     * or terminal state (Completed, Failed) that requires database persistence.
     */
    @Serializable
    @SerialName("workflow_persistence")
    data class WorkflowPersistence(
        val instance: InstanceMessage
    ) : DatabaseMessage()

    /**
     * Infrastructure failure with workflow context.
     *
     * Sent when runner infrastructure fails (DB access, definition retrieval, etc.)
     * but we still have the InstanceMessage for context.
     *
     * @property retryable true = save to RetryOutbox, false = save to FailureModel
     */
    @Serializable
    @SerialName("infrastructure_failure")
    data class InfrastructureFailure(
        val instance: InstanceMessage,
        val errorClass: String,
        val errorMessage: String?,
        val errorStackTrace: String,
        val reason: String,
        val retryable: Boolean
    ) : DatabaseMessage()

    /**
     * Message deserialization failure.
     *
     * Sent when we cannot parse the incoming message into an InstanceMessage.
     * Only contains the raw payload and error information.
     */
    @Serializable
    @SerialName("deserialization_failure")
    data class DeserializationFailure(
        val payload: String,
        val errorClass: String,
        val errorMessage: String?,
        val errorStackTrace: String
    ) : DatabaseMessage()


    companion object {
        fun fromJsonString(jsonString: String): DatabaseMessage =
            com.lemline.common.json.LemlineJson.decodeFromString(jsonString)
    }

    override fun toJsonString(): String =
        com.lemline.common.json.LemlineJson.encodeToString(this)
}
