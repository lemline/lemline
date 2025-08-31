// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.runner.config.CONSUMER_ENABLED
import com.lemline.runner.config.MESSAGING_CONSUMER_CONCURRENCY
import com.lemline.runner.messaging.MessageSubscriber
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher

internal const val WORKFLOW_IN = "workflows-in"

@OptIn(ExperimentalTime::class)
@Startup
@ApplicationScoped
internal class InstanceMessageSubscriber(
    @param:ConfigProperty(name = MESSAGING_CONSUMER_CONCURRENCY) override val maxConcurrency: Int,
    @param:ConfigProperty(name = CONSUMER_ENABLED) override val enabled: Boolean,
    @param:Channel(WORKFLOW_IN) override val publisher: Publisher<Message<String>>,
    override val handler: InstanceMessageHandler,
    override val metrics: InstanceMessageSubscriberMetrics,
) : MessageSubscriber()
