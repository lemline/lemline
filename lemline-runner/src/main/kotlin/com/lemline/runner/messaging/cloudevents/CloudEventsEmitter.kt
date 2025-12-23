// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.logger.logger
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

internal const val CLOUDEVENTS_OUT_CHANNEL = "cloudevents-out"

/**
 * Emitter for CloudEvents to external messaging systems.
 *
 * Publishes CloudEvents in the CloudEvents v1.0 JSON format to the
 * cloudevents-out channel using the official CloudEvents SDK. The CloudEvents
 * include Lemline extension attributes for workflow traceability.
 *
 * This emitter is fire-and-forget - it does not wait for acknowledgment
 * and does not persist events to the database.
 */
@ExperimentalTime
@Startup
@ApplicationScoped
@IfBuildProperty(name = "lemline.messaging.cloudevents.producer.enabled", stringValue = "true", enableIfMissing = false)
internal class CloudEventsEmitter {
    private val logger = logger()

    @Channel(CLOUDEVENTS_OUT_CHANNEL)
    private lateinit var emitter: MutinyEmitter<String>

    /**
     * Sends a CloudEvent to the external channel.
     *
     * Adds Lemline extension attributes for workflow traceability before
     * serializing and sending to the messaging channel.
     *
     * @param cloudEvent The CloudEvent built with the official SDK
     * @param workflowId Optional workflow ID for traceability
     * @param workflowNamespace Optional workflow namespace for traceability
     * @param workflowName Optional workflow name for traceability
     * @param workflowVersion Optional workflow version for traceability
     */
    suspend fun send(
        cloudEvent: CloudEvent,
        workflowId: String? = null,
        workflowNamespace: String? = null,
        workflowName: String? = null,
        workflowVersion: String? = null,
    ) {
        // Add Lemline extension attributes for workflow traceability
        val builder = CloudEventBuilder.from(cloudEvent)
        workflowId?.let { builder.withExtension("lemlineworkflowid", it) }
        workflowNamespace?.let { builder.withExtension("lemlineworkflownamespace", it) }
        workflowName?.let { builder.withExtension("lemlineworkflowname", it) }
        workflowVersion?.let { builder.withExtension("lemlineworkflowversion", it) }

        val enrichedEvent = builder.build()

        // Serialize using the centralized CloudEventService
        val payload = CloudEventService.serialize(enrichedEvent)

        logger.debug { "Emitting CloudEvent: id=${enrichedEvent.id}, source=${enrichedEvent.source}, type=${enrichedEvent.type}" }

        try {
            emitter.sendMessage(Message.of(payload)).awaitSuspending()
            logger.debug { "CloudEvent sent successfully: id=${enrichedEvent.id}" }
        } catch (e: Exception) {
            // Fire-and-forget: log error but don't fail the workflow
            logger.error(e) { "Failed to emit CloudEvent: id=${enrichedEvent.id}" }
        }
    }
}
