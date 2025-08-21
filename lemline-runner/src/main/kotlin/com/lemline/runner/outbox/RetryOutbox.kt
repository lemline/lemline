// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.RetryRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

/**
 * `RetryOutbox` specializes `AbstractOutbox` to implement the outbox pattern for retrying failed operations.
 *
 * This class coordinates retry logic in two main areas:
 * - In workflow execution via [com.lemline.runner.StepByStepRunner]:
 *   - Uses `WorkflowInstance.onRetry()` to handle retries defined by the workflow itself.
 * - In message processing via [com.lemline.runner.messaging.ReactiveMessageHandler]:
 *   - `Message<String>.saveAsFailed()` records non-recoverable failures.
 *   - `Message<String>.saveForRetry()` schedules recoverable failures for future retry attempts.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
internal class RetryOutbox : AbstractOutbox<RetryModel>() {

    @Channel(WORKFLOW_OUT)
    override lateinit var emitter: Emitter<String>

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var repository: RetryRepository

    // Is this outbox enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().retry().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().consumer().enabled()
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().retry().outbox() }

    // Cleanup configuration
    override val cleanupConf by lazy { lemlineConfig.outbox().retry().cleanup() }
}
