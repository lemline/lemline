// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.messaging.MessageSubscriber
import io.cloudevents.CloudEvent
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher

internal const val CLOUDEVENTS_IN_CHANNEL = "cloudevents-in"

/**
 * Subscribes to incoming CloudEvents and processes them by matching against active listeners.
 *
 * CloudEvents are consumed from the `cloudevents-in` channel, parsed using the CloudEvents SDK,
 * and then passed to the [CloudEventHandler] for processing.
 *
 * Unlike workflow message subscribers, CloudEvents don't follow a strict request-response pattern.
 * Each event may trigger zero, one, or multiple listener completions.
 *
 * This class is a thin wrapper that provides configuration to [MessageSubscriber].
 * All subscription lifecycle, backpressure, and graceful shutdown logic is inherited.
 */
@Startup
@ApplicationScoped
internal class CloudEventSubscriber(
    @param:ConfigProperty(name = CLOUDEVENTS_CONSUMER_CONCURRENCY) override val maxConcurrency: Long,
    @param:ConfigProperty(name = CLOUDEVENTS_CONSUMER_ENABLED) override val enabled: Boolean,
    @param:Channel(CLOUDEVENTS_IN_CHANNEL) override val publisher: Publisher<Message<String>>,
    override val handler: CloudEventHandler,
    override val metrics: CloudEventSubscriberMetrics,
) : MessageSubscriber<CloudEvent>()
