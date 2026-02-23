// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kotlin.jvm.optionals.getOrNull
import org.eclipse.microprofile.reactive.messaging.Channel

const val COMMANDS_OUT_CHANNEL = "commands-out"

@Startup
@ApplicationScoped
class WorkflowCommandEmitter(
    config: LemlineConfiguration,
    override val metrics: WorkflowCommandSubscriberMetrics
) : MessageEmitter<InstanceMessage<out WorkflowCommand>>() {
    override val enabled: Boolean = config.messaging().commands().getOrNull()?.producer()?.enabled() ?: false

    @Channel(COMMANDS_OUT_CHANNEL)
    override lateinit var emitter: MutinyEmitter<String>
}
