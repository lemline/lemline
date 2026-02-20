// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.logger.Logger
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.listeners.ListenerEventService
import com.lemline.runner.messaging.MessageHandler
import io.cloudevents.CloudEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Handles CloudEvent messages from the messaging layer.
 *
 * This handler is a thin adapter that:
 * 1. Deserializes incoming messages to CloudEvent objects
 * 2. Delegates processing to [ListenerEventService.handleCloudEvent]
 *
 * All business logic for CloudEvent processing (matching, inserting events,
 * evaluating until conditions) is in [ListenerEventService].
 */
@ApplicationScoped
internal class CloudEventHandler(
    override val metrics: CloudEventSubscriberMetrics,
) : MessageHandler<CloudEvent> {

    override val logger: Logger = logger()

    @Inject
    internal lateinit var listenerEventService: ListenerEventService

    // Test hooks
    override var onCompleteTest: (Message<String>, CloudEvent?) -> Unit = { _, _ -> }
    override var onFailureTest: (Message<String>, Throwable?) -> Unit = { _, _ -> }

    // ========================================
    // MessageHandler implementation
    // ========================================

    override suspend fun Message<String>.deserialize(): CloudEvent {
        return CloudEventService.deserialize(payload)
    }

    override suspend fun handle(current: CloudEvent): CloudEvent? {
        listenerEventService.handleCloudEvent(current)
        // Return null to skip emit - CloudEvents don't produce outbound messages
        return null
    }

    // Never called since handle() returns null
    override suspend fun serialize(current: CloudEvent, next: CloudEvent): String {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }

    // Never called since handle() returns null
    override suspend fun emit(payload: String, idempotentKey: IDV7) {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }

    // Never called since handle() returns null
    override fun deriveIdempotentKey(next: CloudEvent): IDV7 {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }
}
