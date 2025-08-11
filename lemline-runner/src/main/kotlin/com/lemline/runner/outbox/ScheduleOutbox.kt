// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.ScheduleRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

/**
 * ScheduleOutbox is responsible for processing and managing wait messages in the system.
 * It extends AbstractOutbox to leverage the common outbox pattern implementation.
 *
 * This class specifically handles wait messages with configuration optimized for
 * the wait use case, including
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
internal class ScheduleOutbox : AbstractOutbox<ScheduleModel>() {

    @Channel(WORKFLOW_OUT)
    override lateinit var emitter: Emitter<String>

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var repository: ScheduleRepository

    override val enabled by lazy {
        lemlineConfig.outbox().schedule().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().consumer().enabled()
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().schedule().outbox() }

    // Cleanup configuration
    override val cleanupConf by lazy { lemlineConfig.outbox().schedule().cleanup() }
}
