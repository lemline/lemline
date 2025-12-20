// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.lifecycle

import com.lemline.common.logger.logger
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LIFECYCLEEVENTS_OUT_CHANNEL
import com.lemline.runner.messaging.cloudevents.CloudEventService
import io.cloudevents.CloudEvent
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
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
@ExperimentalTime
@Startup
@ApplicationScoped
@IfBuildProperty(name = LIFECYCLE_EVENTS_PRODUCER_ENABLED, stringValue = "true", enableIfMissing = false)
class LifecycleEventEmitterImpl : LifecycleEventEmitter {
    private val logger = logger()

    @Channel(LIFECYCLEEVENTS_OUT_CHANNEL)
    private lateinit var emitter: MutinyEmitter<String>

    override suspend fun emit(cloudEvent: CloudEvent) {
        val payload = CloudEventService.serialize(cloudEvent)

        logger.debug {
            "Emitting lifecycle event: id=${cloudEvent.id}, type=${cloudEvent.type}, source=${cloudEvent.source}"
        }

        try {
            emitter.sendMessage(Message.of(payload)).awaitSuspending()
            logger.trace { "Lifecycle event sent: id=${cloudEvent.id}" }
        } catch (e: Exception) {
            // Fire-and-forget: log warning but never throw
            // Lifecycle events are observability data - failures should not impact workflow execution
            logger.warn(e) {
                "Failed to emit lifecycle event: id=${cloudEvent.id}, type=${cloudEvent.type}"
            }
        }
    }
}
