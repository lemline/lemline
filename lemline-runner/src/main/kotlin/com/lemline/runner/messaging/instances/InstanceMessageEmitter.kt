// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.instances

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
internal class InstanceMessageEmitter(
    override val metrics: InstanceMessageSubscriberMetrics
) : MessageEmitter<InstanceMessage>() {

    @Channel(WORKFLOWS_OUT_CHANNEL)
    override lateinit var emitter: MutinyEmitter<String>
}
