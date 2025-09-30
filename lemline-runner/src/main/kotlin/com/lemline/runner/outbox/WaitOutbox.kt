// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.models.WaitModel
import com.lemline.runner.outbox.bases.Outbox
import com.lemline.runner.outbox.bases.Scheduler
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.WaitRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * This class manages the restart of workflow instances after a wait task.
 * Integration occurs via the `WorkflowInstance.onWait` method in [com.lemline.runner.StepByStepRunner].
 *
 *  Inherits core scheduling functionality from the [Scheduler] class, integrating Quarkus lifecycle
 *  events to ensure proper startup and shutdown behavior.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class WaitOutbox : Scheduler() {

    @Inject
    private lateinit var instanceEmitter: InstanceMessageEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var failureRepository: FailureRepository

    @Inject
    private lateinit var outboxRepository: WaitRepository

    override val description: String = "Waits table outbox"

    override val schedulable by lazy {
        Outbox(
            failureRepository = failureRepository,
            outboxRepository = outboxRepository,
            outboxConfig = lemlineConfig.database().tables().waits().outbox(),
        ) { entity: WaitModel ->
            instanceEmitter.send(entity.instanceMessage)
        }
    }
}
