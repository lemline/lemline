// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.config.COMMANDS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
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

internal const val COMMANDS_IN_CHANNEL = "commands-in"

@ExperimentalTime
@ExperimentalSerializationApi
@Startup
@ApplicationScoped
internal class WorkflowCommandSubscriber(
    @param:ConfigProperty(name = COMMANDS_CONSUMER_CONCURRENCY) override val maxConcurrency: Long,
    @param:ConfigProperty(name = COMMANDS_CONSUMER_ENABLED) override val enabled: Boolean,
    @param:Channel(COMMANDS_IN_CHANNEL) override val publisher: Publisher<Message<String>>,
    override val handler: WorkflowCommandHandler,
    override val metrics: WorkflowCommandSubscriberMetrics,
) : MessageSubscriber<InstanceMessage<WorkflowCommand>>()
