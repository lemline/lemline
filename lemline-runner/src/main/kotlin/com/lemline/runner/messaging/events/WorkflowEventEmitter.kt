// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel

internal const val EVENTS_OUT_CHANNEL = "events-out"

@Startup
@ApplicationScoped
internal class WorkflowEventEmitter(
    @param:ConfigProperty(name = EVENTS_PRODUCER_ENABLED) override val enabled: Boolean,
    override val metrics: WorkflowEventSubscriberMetrics
) : MessageEmitter<InstanceMessage<WorkflowEvent>>() {
    @Channel(EVENTS_OUT_CHANNEL)
    override lateinit var emitter: MutinyEmitter<String>
}
