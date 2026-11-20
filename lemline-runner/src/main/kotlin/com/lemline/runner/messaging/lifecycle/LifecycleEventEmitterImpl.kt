// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.lifecycle

import com.lemline.common.logger.logger
import com.lemline.core.cloudevents.CloudEventParser.toReadableString
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.runner.common.lifecycle.LifecycleEventPublisher
import com.lemline.runner.common.messaging.LIFECYCLEEVENTS_OUT_CHANNEL
import com.lemline.runner.config.LemlineConfiguration
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Production implementation of [LifecycleEventEmitter] and [LifecycleEventPublisher].
 *
 * Publishes lifecycle events (workflow.started, task.completed, etc.) to the
 * `lifecycleevents-out` channel for external observability systems.
 *
 * As a [LifecycleEventEmitter] (used by workers for execution events), it is fire-and-forget:
 * failures are logged but never thrown, so they never impact workflow execution, and nothing is
 * persisted.
 *
 * As a [LifecycleEventPublisher] (used by the creation event outbox for creation events), it waits for
 * the broker's acknowledgment and throws on failure, so the outbox retries the event.
 */
@Startup
@ApplicationScoped
class LifecycleEventEmitterImpl : LifecycleEventEmitter, LifecycleEventPublisher {
    private val logger = logger()

    @Channel(LIFECYCLEEVENTS_OUT_CHANNEL)
    private lateinit var emitter: MutinyEmitter<String>

    @Inject
    lateinit var config: LemlineConfiguration

    override val enabled: Boolean by lazy {
        config.messaging().lifecycleevents().getOrNull()?.producer()?.enabled() == true
    }

    override suspend fun publish(payload: String) {
        emitter.sendMessage(Message.of(payload)).awaitSuspending()
    }

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
