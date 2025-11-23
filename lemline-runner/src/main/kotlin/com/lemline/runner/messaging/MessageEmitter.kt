// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.json.JsonSerializable
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WithOptionalWorkflowInfo
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Base class for message emitters.
 * Generic type T must be JsonSerializable to support toJsonString() method.
 */
@ExperimentalTime
internal abstract class MessageEmitter<T : JsonSerializable> {

    protected abstract val emitter: MutinyEmitter<String>

    protected abstract val metrics: MessageSubscriberMetrics

    @Inject
    private lateinit var messageMetaData: MessageMetaData

    private val logger = logger()

    // Retrieve workflowInfo if present
    private val T?.workflowInfo get() = (this as? WithOptionalWorkflowInfo)?.workflowInfo

    suspend fun sendPayload(payload: String) {
        val md = MetaData(messageId = IDV7.random())
        retry(
            logger = logger,
            label = "Emit message",
            maxAttempts = 6,
            totalBudgetMs = 6_000,
            singleAttemptTimeoutMs = 1_000
        ) {
            emit(payload, md)
        }
    }

    suspend fun send(msg: T) {
        val payload = metrics.recordSerializationDuration(msg.workflowInfo) {
            msg.toJsonString()
        }
        sendPayload(payload)
    }

    private suspend fun emit(payload: String, metadata: MetaData) {
        val msg = Message.of(payload)
        with(messageMetaData) { msg.addMetaData(metadata) }

        emitter.sendMessage(msg).awaitSuspending()
    }
}
