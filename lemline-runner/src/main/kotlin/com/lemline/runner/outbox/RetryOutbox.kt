// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.RetryRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

/**
 * RetryOutbox is responsible for processing and managing retry messages in the system.
 * It extends AbstractOutbox to leverage the common outbox pattern implementation.
 *
 * This class specifically handles retry messages with configuration optimized for
 * the retry use case, including
 * - Processing batch size
 * - Maximum retry attempts
 * - Initial delay between retries
 * - Cleanup retention period
 *
 * @see AbstractOutbox for the base implementation
 * @see OutboxProcessor for the core message processing logic
 */
@Startup
@ApplicationScoped
internal class RetryOutbox : AbstractOutbox<RetryModel>() {

    @Inject
    @Channel(WORKFLOW_OUT)
    override lateinit var emitter: Emitter<String>

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var repository: RetryRepository

    override val enabled by lazy { lemlineConfig.messaging().consumer().enabled() }

    // Outbox processing configuration
    private val outboxConf by lazy { lemlineConfig.retry().outbox() }
    override val outboxBatchSize by lazy { outboxConf.batchSize() }
    override val outboxMaxAttempts by lazy { outboxConf.maxAttempts() }
    override val outboxInitialDelay by lazy { outboxConf.initialDelay() }
    override val outboxExecutionPeriod by lazy { outboxConf.every() }

    // Cleanup configuration
    private val cleanupConf by lazy { lemlineConfig.retry().cleanup() }
    override val cleanupAfter by lazy { cleanupConf.after() }
    override val cleanupBatchSize by lazy { cleanupConf.batchSize() }
    override val cleanupExecutionPeriod by lazy { cleanupConf.every() }
}
