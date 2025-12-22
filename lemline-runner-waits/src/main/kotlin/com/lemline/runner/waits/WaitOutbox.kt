// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.waits

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * `WaitOutbox` specializes `AbstractOutbox` to implement the outbox pattern for wait tasks in workflows.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class WaitOutbox : AbstractOutbox<WaitModel>() {

    override val jobName: String get() = "Waits outbox"

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var waitConfig: WaitConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var waitRepository: WaitRepository

    override val outboxRepository: WithOutboxRepository<WaitModel> get() = waitRepository

    override val crudRepository: WithCrudRepository<WaitModel> get() = waitRepository

    // Is this outbox enabled?
    override val enabled by lazy { waitConfig.enabled }

    // Outbox processing configuration
    override val outboxConfig: OutboxConfig? by lazy { waitConfig.outbox }

    /**
     * Transform WaitStarted Event -> ResumeFromStartedTask Command before sending.
     * This ensures the workflow handler receives a command it can process.
     *
     * Uses idempotent message ID derived from the wait model's ID to ensure
     * duplicate processing produces the same message ID.
     */
    override suspend fun process(entity: WaitModel) {
        // Derive message ID from the wait model's ID
        val messageId = entity.id.derive("-resume")

        commandEmitter.send(
            InstanceMessage(
                workflowInfo = entity.instanceMessage.workflowInfo,
                workflowState = entity.instanceMessage.workflowState.resume(),
            ),
            messageId
        )

        // Mark the wait model to be cleaned up
        entity.cleanupAfter = Clock.System.now()
    }
}
