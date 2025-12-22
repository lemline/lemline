// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

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
 * `RetryOutbox` specializes `AbstractOutbox` to implement the outbox pattern for retrying failed operations.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class RetryOutbox : AbstractOutbox<RetryModel>() {

    override val jobName: String get() = "Retries outbox"

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var retryConfig: RetryConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var retryRepository: RetryRepository

    override val outboxRepository: WithOutboxRepository<RetryModel> get() = retryRepository

    override val crudRepository: WithCrudRepository<RetryModel> get() = retryRepository

    // Is this outbox enabled?
    override val enabled by lazy { retryConfig.enabled }

    // Outbox processing configuration
    override val outboxConfig: OutboxConfig? by lazy { retryConfig.outbox }

    /**
     * Transform RetryScheduled Event -> ResumeFromTask Command before sending.
     * This ensures the workflow handler receives a command it can process.
     *
     * Uses idempotent message ID derived from the retry model's ID to ensure
     * duplicate processing produces the same message ID.
     */
    override suspend fun process(entity: RetryModel) {
        // Derive message ID from the retry model's ID
        val messageId = entity.id.derive("-resume")

        commandEmitter.send(
            InstanceMessage(
                workflowInfo = entity.instanceMessage.workflowInfo,
                workflowState = entity.instanceMessage.workflowState.resume(),
            ),
            messageId
        )

        // Mark the retry model to be cleaned up
        entity.cleanupAfter = Clock.System.now()
    }
}
