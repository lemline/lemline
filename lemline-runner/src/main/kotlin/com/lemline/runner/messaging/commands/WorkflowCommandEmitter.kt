// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Channel

const val COMMANDS_OUT_CHANNEL = "commands-out"

@ExperimentalTime
@Startup
@ApplicationScoped
class WorkflowCommandEmitter(
    override val metrics: WorkflowCommandSubscriberMetrics
) : MessageEmitter<InstanceMessage<out WorkflowCommand>>() {

    @Channel(COMMANDS_OUT_CHANNEL)
    override lateinit var emitter: MutinyEmitter<String>
}
