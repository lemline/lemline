// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ParentRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

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
@ExperimentalSerializationApi
internal class ParentOutbox : AbstractOutbox<ParentOutboxModel>() {

    @Inject
    override lateinit var instanceEmitter: InstanceMessageEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var failureRepository: FailureRepository

    @Inject
    override lateinit var outboxRepository: ParentRepository

    // Is this outbox enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().retry().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().workflows().getOrNull()?.consumer()?.enabled() ?: false
    }
    
    /**
     * Messages in this table are not sent by an outbox process
     *  but during the processing of [com.lemline.runner.messaging.database.CompletedMessage]
     */
    override val outboxConf = null

    // Cleanup configuration
    override val cleanupConf by lazy { lemlineConfig.outbox().parent().cleanup() }
}
