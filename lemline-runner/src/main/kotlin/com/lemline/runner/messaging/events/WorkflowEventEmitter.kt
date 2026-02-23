// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kotlin.jvm.optionals.getOrNull
import org.eclipse.microprofile.reactive.messaging.Channel

internal const val EVENTS_OUT_CHANNEL = "events-out"

@Startup
@ApplicationScoped
internal class WorkflowEventEmitter(
    config: LemlineConfiguration,
    override val metrics: WorkflowEventSubscriberMetrics
) : MessageEmitter<InstanceMessage<WorkflowEvent>>() {
    override val enabled: Boolean = config.messaging().events().getOrNull()?.producer()?.enabled() ?: false

    @Channel(EVENTS_OUT_CHANNEL)
    override lateinit var emitter: MutinyEmitter<String>
}
