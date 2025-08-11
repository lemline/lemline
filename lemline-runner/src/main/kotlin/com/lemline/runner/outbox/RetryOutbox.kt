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

    @Channel(WORKFLOW_OUT)
    override lateinit var emitter: Emitter<String>

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var repository: RetryRepository

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
