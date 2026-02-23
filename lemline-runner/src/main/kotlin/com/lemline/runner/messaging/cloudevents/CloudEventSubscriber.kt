// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.runner.common.messaging.MessageSubscriber
import com.lemline.runner.config.shared.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.shared.LemlineConfiguration
import io.cloudevents.CloudEvent
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import kotlin.jvm.optionals.getOrNull
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
    config: LemlineConfiguration,
    @param:Channel(CLOUDEVENTS_IN_CHANNEL) override val publisher: Publisher<Message<String>>,
    override val handler: CloudEventHandler,
    override val metrics: CloudEventSubscriberMetrics,
) : MessageSubscriber<CloudEvent>() {
    private val consumerConfig = config.messaging().cloudevents().getOrNull()?.consumer()

    override val maxConcurrency: Long = consumerConfig?.concurrency() ?: CONSUMER_CONCURRENCY_DEFAULT.toLong()

    override val enabled: Boolean = consumerConfig?.enabled() ?: false
}
