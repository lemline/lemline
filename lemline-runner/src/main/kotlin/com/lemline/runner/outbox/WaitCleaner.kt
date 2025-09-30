// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.outbox.bases.Cleaner
import com.lemline.runner.outbox.bases.Scheduler
import com.lemline.runner.repositories.WaitRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
internal class WaitCleaner : Scheduler() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var waitRepository: WaitRepository

    override val description: String = "Waits table cleaning"

    override val schedulable by lazy {
        Cleaner(
            cleanerRepository = waitRepository,
            cleanerConfig = lemlineConfig.database().tables().waits().cleanup()
        )
    }
}
