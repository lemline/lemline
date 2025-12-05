// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ListenerRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Outbox processor for listener completions.
 *
 * Processes listeners that are ready for completion (outbox_delayed_until <= NOW).
 * Uses the standard AbstractOutbox pattern with exponential backoff retry.
 *
 * ## How it works
 *
 * Listeners start with `outbox_delayed_until = NULL` (not picked up by outbox).
 * When a CloudEvent matches:
 * 1. CloudEventHandler sets `event` column AND `outbox_delayed_until = NOW()`
 *    - ONE/ANY: single event JSON (wrapped in array at completion)
 *    - ALL: aggregated JSON array of all matched events
 * 2. This outbox picks it up via standard `findEntitiesToProcess()`
 * 3. Sends resume command using the stored event(s)
 * 4. AbstractOutbox marks `outbox_completed_at = NOW()`
 *
 * On failure, AbstractOutbox handles retry with exponential backoff automatically.
 *
 * ## Race Condition Prevention
 *
 * This design prevents the double-completion race condition:
 * - Multiple runners may receive the same CloudEvent
 * - CloudEventHandler uses atomic UPDATE with WHERE guards
 * - Only ONE runner succeeds in setting `outbox_delayed_until = NOW()`
 * - Only ONE listener completion is processed by this outbox
 *
 * @see AbstractOutbox for base outbox pattern implementation
 * @see com.lemline.runner.messaging.cloudevents.CloudEventHandler for event matching
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenerCompletionOutbox : AbstractOutbox<ListenerModel>() {

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var failureRepository: FailureRepository

    @Inject
    override lateinit var outboxRepository: ListenerRepository

    /** Is this outbox enabled? */
    override val enabled by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.enabled()?.getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    /** Outbox processing configuration - fallback to wait config if listener config not set */
    override val outboxConf by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.outbox()
            ?: lemlineConfig.outbox().wait().outbox()
    }

    /** Cleanup configuration - fallback to wait config if listener config not set */
    override val cleanerConf by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.cleanup()
            ?: lemlineConfig.outbox().wait().cleanup()
    }

    /**
     * Process a single listener completion.
     * Sends the resume command with the collected events.
     *
     * The `entity.event` column contains:
     * - ONE/ANY: Single event JSON (wrapped in array for consistent output)
     * - ALL: Already a JSON array of all matched events
     */
    override suspend fun process(entity: ListenerModel) {
        val eventJson = requireNotNull(entity.event) {
            "Listener ${entity.id} has no event but was picked up for completion processing"
        }

        // Parse the stored event(s)
        val parsedEvent = Json.parseToJsonElement(eventJson)

        // Create a resume command with parsedEvent as the task output
        val resumeCommand = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = entity.instanceMessage.workflowState.nodeStack,
            rawOutput = parsedEvent
        )

        val resumeMessage = InstanceMessage(
            workflowInfo = entity.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        // Derive idempotent message ID from listener ID
        val messageId = entity.id.derive("-listen-complete")

        instanceEmitter.send(resumeMessage, messageId)

        logger.info {
            "Resume command sent for listener ${entity.id}, workflow ${entity.workflowId} " +
                "at position ${entity.nodePosition}"
        }
    }
}
