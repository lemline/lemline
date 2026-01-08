// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.messaging.MessageSubscriber
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher

internal const val EVENTS_IN_CHANNEL = "events-in"

@ExperimentalTime
@ExperimentalSerializationApi
@Startup
@ApplicationScoped
internal class WorkflowEventSubscriber(
    @param:ConfigProperty(name = EVENTS_CONSUMER_CONCURRENCY) override val maxConcurrency: Long,
    @param:ConfigProperty(name = EVENTS_CONSUMER_ENABLED) override val enabled: Boolean,
    @param:Channel(EVENTS_IN_CHANNEL) override val publisher: Publisher<Message<String>>,
    override val handler: WorkflowEventHandler,
    override val metrics: WorkflowEventSubscriberMetrics,
) : MessageSubscriber<InstanceMessage<WorkflowEvent>>()
