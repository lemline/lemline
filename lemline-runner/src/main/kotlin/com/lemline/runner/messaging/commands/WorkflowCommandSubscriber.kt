// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.core.states.WorkflowCommand
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

internal const val COMMANDS_IN_CHANNEL = "commands-in"

@Startup
@ApplicationScoped
internal class WorkflowCommandSubscriber(
    config: LemlineConfiguration,
    @param:Channel(COMMANDS_IN_CHANNEL) override val publisher: Publisher<Message<String>>,
    override val handler: WorkflowCommandHandler,
    override val metrics: WorkflowCommandSubscriberMetrics,
) : MessageSubscriber<InstanceMessage<WorkflowCommand>>() {
    private val consumerConfig = config.messaging().commands().getOrNull()?.consumer()

    override val maxConcurrency: Long = consumerConfig?.concurrency() ?: CONSUMER_CONCURRENCY_DEFAULT.toLong()

    override val enabled: Boolean = consumerConfig?.enabled() ?: false
}
