// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.WaitRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

/**
 * `WaitOutbox` specializes `AbstractOutbox` to implement the outbox pattern for wait tasks in workflows.
 *
 * It manages the restart of workflow instances after a wait task.
 * Integration occurs via the `WorkflowInstance.onWait` method in [com.lemline.runner.StepByStepRunner].
 */
@Startup
@ApplicationScoped
@ExperimentalTime
internal class WaitOutbox : AbstractOutbox<WaitModel>() {

    @Channel(WORKFLOW_OUT)
    override lateinit var emitter: Emitter<String>

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var repository: WaitRepository

    // Is this outbox enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().wait().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().consumer().enabled()
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().wait().outbox() }

    // Cleanup configuration
    override val cleanupConf by lazy { lemlineConfig.outbox().wait().cleanup() }
}
