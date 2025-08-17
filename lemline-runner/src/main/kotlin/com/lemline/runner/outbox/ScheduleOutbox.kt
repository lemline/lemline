// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.MessageBody
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.ScheduleRepository
import io.quarkus.runtime.Startup
import io.quarkus.smallrye.reactivemessaging.sendSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
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
@ExperimentalTime
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

    @ExperimentalTime
    override suspend fun process(entity: ScheduleModel) {
        // delayedUntil is never null within the scope of this method
        val now = entity.outboxDelayedUntil!!

        // start a new instance of the workflow
        send(entity.toMessageBody())

        // update the schedule model with the next instant to be processed
        val delayedUntil = when {
            entity.after != null -> null // <- next instant remains undefined. It's set only after the instance's completion

            entity.cron != null -> entity.getNextScheduledExecutionInstant().also {
                // if there is no more occurrence, we set the status to SENT for allowing cleaning
                if (it == null) entity.outBoxStatus = OutBoxStatus.SENT
            }

            entity.every != null -> now + entity.every!!

            else -> error("Invalid schedule model")
        }

        // Note: we don't update the status to SENT as the schedule model is still waiting for the next execution.
        entity.outboxDelayedUntil = delayedUntil
        if (delayedUntil != null) entity.outboxScheduledFor = delayedUntil
    }

    private suspend fun send(body: MessageBody) =
        emitter.sendSuspending(body.jsonString.replace(ID_PLACEHOLDER, IdGenerator.generateTimeBasedId()))

    companion object {
        private const val ID_PLACEHOLDER = "`WORKFLOW_ID`"
    }
}
