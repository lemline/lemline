// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.lifecycle

import com.lemline.common.logger.logger
import com.lemline.core.cloudevents.CloudEventParser.toReadableString
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.runner.messaging.lifecycle.LIFECYCLEEVENTS_OUT_CHANNEL
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Production implementation of [LifecycleEventEmitter].
 *
 * Publishes lifecycle events (workflow.started, task.completed, etc.) to the
 * `lifecycleevents-out` channel for external observability systems.
 *
 * This emitter follows a fire-and-forget pattern:
 * - Events are sent asynchronously without waiting for acknowledgment
 * - Failures are logged but never thrown to avoid impacting workflow execution
 * - No database persistence - events go directly to the messaging channel
 *
 * Only instantiated when `lemline.lifecycle-events-producer-enabled=true`.
 */
@Startup
@ApplicationScoped
class LifecycleEventEmitterImpl : LifecycleEventEmitter {
    private val logger = logger()

    @Channel(LIFECYCLEEVENTS_OUT_CHANNEL)
    private lateinit var emitter: MutinyEmitter<String>

    override suspend fun emit(cloudEvent: CloudEvent) {
        val payload = CloudEventService.serialize(cloudEvent)

        logger.trace { "Emitting lifecycle event: ${cloudEvent.toReadableString()}" }
        try {
            emitter.sendMessage(Message.of(payload)).awaitSuspending()
            logger.debug { "Lifecycle event sent: ${cloudEvent.toReadableString()}" }
        } catch (e: Exception) {
            // Fire-and-forget: log warning but never throw
            // Lifecycle events are observability data - failures should not impact workflow execution
            logger.warn(e) { "Failed to emit lifecycle event: ${cloudEvent.toReadableString()}" }
        }
    }
}
