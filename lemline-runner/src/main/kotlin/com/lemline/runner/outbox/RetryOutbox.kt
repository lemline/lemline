// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
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
internal class RetryOutbox : AbstractOutbox<RetryModel>() {

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var failureRepository: FailureRepository

    @Inject
    override lateinit var outboxRepository: RetryRepository

    override val crudRepository: WithCrudRepository<RetryModel> get() = outboxRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

    // Is this outbox enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().retry().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    // Outbox processing configuration
    override val outboxConf by lazy { lemlineConfig.outbox().retry().outbox() }

    // Cleanup configuration
    override val cleanerConf by lazy { lemlineConfig.outbox().retry().cleanup() }

    /**
     * Transform RetryScheduled Event → ResumeFromTask Command before sending.
     * This ensures the workflow handler receives a command it can process.
     *
     * Uses idempotent message ID derived from the retry model's ID to ensure
     * duplicate processing produces the same message ID.
     */
    override suspend fun process(entity: RetryModel) {
        // Derive message ID from the retry model's ID
        val messageId = entity.id.derive("-resume")

        instanceEmitter.send(
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
