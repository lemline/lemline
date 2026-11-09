// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.destinationFor
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.messaging.MessageMetaData
import com.lemline.runner.messaging.MetaData
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import kotlin.jvm.optionals.getOrNull
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
@Startup
@ApplicationScoped
internal class CloudEventsEmitter(
    config: LemlineConfiguration,
    private val messageMetaData: MessageMetaData,
) {
    private val enabled: Boolean = config.messaging().cloudevents().getOrNull()?.producer()?.enabled() ?: false

    private val destinationPrefix = config.messaging().prefix()

    private val logger = logger()

    @PostConstruct
    fun init() {
        if (enabled) {
            logger.info { "✅ CloudEvents emitter enabled" }
        } else {
            logger.info { "❌ CloudEvents emitter disabled" }
        }
    }

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
        if (!enabled) {
            logger.warn { "CloudEvents emitter is disabled, event will not be sent: id=${cloudEvent.id}" }
            return
        }

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
            // Emit tasks publish to the emitting workflow's namespace destination
            // (<prefix>-<namespace>-cloudevents); cross-namespace targeting is out of scope.
            val metaData = MetaData(
                messageId = IDV7.random(),
                destination = workflowNamespace?.let {
                    destinationFor(destinationPrefix, it, DestinationType.CLOUDEVENTS)
                },
                namespace = workflowNamespace,
            )
            val message = with(messageMetaData) { Message.of(payload).withMetaData(metaData) }
            emitter.sendMessage(message).awaitSuspending()
            logger.debug { "CloudEvent sent successfully: id=${enrichedEvent.id}" }
        } catch (e: Exception) {
            // Fire-and-forget: log error but don't fail the workflow
            logger.error(e) { "Failed to emit CloudEvent: id=${enrichedEvent.id}" }
        }
    }
}
