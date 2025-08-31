// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel

internal const val WORKFLOW_OUT = "workflows-out"

@Startup
@ApplicationScoped
internal class InstanceMessageEmitter : MessageEmitter() {

    @Channel(WORKFLOW_OUT)
    override lateinit var emitter: MutinyEmitter<String>
}
