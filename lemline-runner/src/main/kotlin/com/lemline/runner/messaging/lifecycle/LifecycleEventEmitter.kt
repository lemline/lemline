// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.lifecycle

import com.lemline.common.logger.logger
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LIFECYCLEEVENTS_OUT_CHANNEL
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Emitter for workflow lifecycle CloudEvents.
 *
 * Publishes lifecycle events (workflow.started, task.completed, etc.) to the
 * `lifecycleevents-out` channel for external observability systems.
 *
 * This emitter follows a fire-and-forget pattern:
 * - Events are sent asynchronously without waiting for acknowledgment
 * - Failures are logged but never thrown to avoid impacting workflow execution
 * - No database persistence - events go directly to the messaging channel
 *
 * @see LifecycleEventHookImpl for building CloudEvents from workflow state
 */
@ExperimentalTime
@Startup
@ApplicationScoped
@IfBuildProperty(name = LIFECYCLE_EVENTS_PRODUCER_ENABLED, stringValue = "true", enableIfMissing = false)
class LifecycleEventEmitter {
    private val logger = logger()

    @Channel(LIFECYCLEEVENTS_OUT_CHANNEL)
    private lateinit var emitter: MutinyEmitter<String>

    private val jsonFormat = JsonFormat()

    /**
     * Emits a lifecycle CloudEvent to the lifecycle events channel.
     *
     * This method is fire-and-forget:
     * - Returns immediately after queuing the event
     * - Never throws exceptions (logs warnings on failure)
     * - Does not block workflow execution
     *
     * @param cloudEvent The CloudEvent to emit (already includes all attributes and extensions)
     */
    suspend fun emit(cloudEvent: CloudEvent) {
        val payload = String(jsonFormat.serialize(cloudEvent), Charsets.UTF_8)

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
