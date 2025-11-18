// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.config.WORKFLOWS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.WORKFLOWS_CONSUMER_ENABLED
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.MessageSubscriber
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher

internal const val WORKFLOWS_IN_CHANNEL = "workflows-in"

@ExperimentalTime
@ExperimentalSerializationApi
@Startup
@ApplicationScoped
internal class WorkflowCommandSubscriber(
    @param:ConfigProperty(name = WORKFLOWS_CONSUMER_CONCURRENCY) override val maxConcurrency: Long,
    @param:ConfigProperty(name = WORKFLOWS_CONSUMER_ENABLED) override val enabled: Boolean,
    @param:Channel(WORKFLOWS_IN_CHANNEL) override val publisher: Publisher<Message<String>>,
    override val handler: WorkflowCommandHandler,
    override val metrics: WorkflowCommandSubscriberMetrics,
) : MessageSubscriber<InstanceMessage<WorkflowCommand>>()
