// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WithOptionalWorkflowInfo
import com.lemline.common.values.WorkflowNamespace
import com.lemline.runner.common.messaging.MessageSubscriberMetrics
import com.lemline.runner.common.messaging.TransportSerializable
import com.lemline.runner.common.messaging.retry
import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.destinationFor
import com.lemline.runner.config.LemlineConfiguration
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.annotation.PostConstruct
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Base class for message emitters.
 * Generic type T must be TransportSerializable to support transport payload encoding.
 *
 * Every message is routed to the destination of its namespace (`<prefix>-<namespace>-<type>`) —
 * a pure function of the message, no routing configuration.
 */
abstract class MessageEmitter<T : TransportSerializable> {

    protected abstract val emitter: MutinyEmitter<String>

    protected abstract val metrics: MessageSubscriberMetrics

    protected abstract val enabled: Boolean

    /** Destination family of this emitter, used by the per-namespace naming convention. */
    protected abstract val destinationType: DestinationType

    @Inject
    internal lateinit var messageMetaData: MessageMetaData

    @Inject
    internal lateinit var lemlineConfiguration: LemlineConfiguration

    private val logger = logger()

    private val destinationPrefix by lazy { lemlineConfiguration.messaging().prefix() }

    @PostConstruct
    fun init() {
        if (enabled) {
            logger.info { "✅ Emitter enabled" }
        } else {
            logger.info { "❌ Emitter disabled" }
        }
    }

    // Retrieve workflowInfo if present
    private val T?.workflowInfo get() = (this as? WithOptionalWorkflowInfo)?.workflowInfo

    /**
     * Sends a pre-serialized payload with an optional idempotent key.
     *
     * @param payload The serialized message payload
     * @param idempotentKey Optional deterministic ID for message deduplication.
     *                      If null, a random IDV7 is generated.
     * @param namespace The namespace of the message, routing it to that namespace's destination.
     */
    suspend fun sendPayload(payload: String, idempotentKey: IDV7? = null, namespace: WorkflowNamespace?) {
        if (!enabled) {
            logger.warn { "Producer is disabled, message will not be sent" }
            return
        }
        val md = MetaData(
            messageId = idempotentKey ?: IDV7.random(),
            destination = namespace?.let { destinationFor(destinationPrefix, it, destinationType) },
            namespace = namespace?.toString(),
        )
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

    /**
     * Sends a message with an optional idempotent key.
     *
     * @param msg The message to send
     * @param idempotentKey Optional deterministic ID for message deduplication.
     *                      If null, a random IDV7 is generated.
     */
    suspend fun send(msg: T, idempotentKey: IDV7? = null) {
        val payload = metrics.recordSerializationDuration(msg.workflowInfo) {
            msg.toTransportPayload()
        }
        sendPayload(payload, idempotentKey, msg.workflowInfo?.namespace)
    }

    private suspend fun emit(payload: String, metadata: MetaData) {
        val msg = with(messageMetaData) { Message.of(payload).withMetaData(metadata) }

        emitter.sendMessage(msg).awaitSuspending()
    }
}
