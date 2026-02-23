// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.messaging.MessageSubscriber
import com.lemline.runner.config.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.LemlineConfiguration
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import kotlin.jvm.optionals.getOrNull
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher

internal const val EVENTS_IN_CHANNEL = "events-in"

@Startup
@ApplicationScoped
internal class WorkflowEventSubscriber(
    config: LemlineConfiguration,
    @param:Channel(EVENTS_IN_CHANNEL) override val publisher: Publisher<Message<String>>,
    override val handler: WorkflowEventHandler,
    override val metrics: WorkflowEventSubscriberMetrics,
) : MessageSubscriber<InstanceMessage<WorkflowEvent>>() {
    private val consumerConfig = config.messaging().events().getOrNull()?.consumer()

    override val maxConcurrency: Long = consumerConfig?.concurrency() ?: CONSUMER_CONCURRENCY_DEFAULT.toLong()

    override val enabled: Boolean = consumerConfig?.enabled() ?: false
}
