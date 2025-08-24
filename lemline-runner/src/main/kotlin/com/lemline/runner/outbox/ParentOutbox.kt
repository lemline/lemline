// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.ReactiveMessageEmitter
import com.lemline.runner.models.ParentModel
import com.lemline.runner.repositories.ParentRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime

/**
 * `RunWorkflowOutbox` specializes `AbstractOutbox` to implement the outbox pattern for child workflow execution events.
 *
 * It is responsible for publishing messages when:
 * - A child workflow is started via `WorkflowInstance.onRunWorkflow()`
 * - A parent workflow is restarted after completion via `WorkflowInstance.onWorkflowCompleted()`
 * in [com.lemline.runner.StepByStepRunner]
 */
@Startup
@ApplicationScoped
@ExperimentalTime
internal class ParentOutbox : AbstractOutbox<ParentModel>() {

    @Inject
    override lateinit var emitter: ReactiveMessageEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var repository: ParentRepository

    // Is this outbox enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().retry().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().consumer().enabled()
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().runWorkflow().outbox() }

    // Cleanup configuration
    override val cleanupConf by lazy { lemlineConfig.outbox().runWorkflow().cleanup() }
}
