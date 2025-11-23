// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Channel

internal const val WORKFLOWS_OUT_CHANNEL = "workflows-out"

@ExperimentalTime
@Startup
@ApplicationScoped
internal class WorkflowCommandEmitter(
    override val metrics: WorkflowCommandSubscriberMetrics
) : MessageEmitter<InstanceMessage<out WorkflowCommand>>() {

    @Channel(WORKFLOWS_OUT_CHANNEL)
    override lateinit var emitter: MutinyEmitter<String>
}
