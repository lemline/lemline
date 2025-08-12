// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.utils.IdGenerator
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.ScheduleRepository
import io.quarkus.runtime.Startup
import io.quarkus.smallrye.reactivemessaging.sendSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
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

    override suspend fun process(model: ScheduleModel) {
        // delayedUntil is never null within the scope of this method
        val now = model.delayedUntil!!

        // start new instance of the workflow
        send(model.message)

        model.delayedUntil = when {
            model.cron != null -> getNextInstantCron(model.cron)

            // here next instant remains undefined as it is set only after the completion of the current instance
            model.after != null -> null

            model.every != null -> now + getDuration(model.every)

            else -> error("Invalid schedule model")
        }
    }

    // get next instant according to the provided cron expression
    private fun getNextInstantCron(cron: String): Instant? = TODO()

    private fun getDuration(d: String): Duration? = Duration.parse(d)

    private suspend fun send(message: String) =
        emitter.sendSuspending(message.replace(ID_PLACEHOLDER, IdGenerator.generateTimeBasedId()))

    companion object {
        private const val ID_PLACEHOLDER = "`ID`"
    }
}
