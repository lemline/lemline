// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.values.Token
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.models.ListenerStrategy
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ListenerRepository
import com.lemline.runner.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

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

    override val crudRepository: WithCrudRepository<ListenerModel> get() = outboxRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

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
     *
     * ## Foreach Support
     *
     * For ONE/ANY strategies with foreach enabled:
     * - Instead of completing the listen task, we resume to foreach.do
     * - The event becomes the input to foreach.do
     * - When foreach.do completes, ListenForEachCompleted is emitted
     * - WorkflowEventHandler.handleListenForEachCompleted then completes the listen task
     *
     * For ALL/ANY+until with foreach:
     * - Events are processed via ListenerEventOutbox (not this outbox)
     * - After all iterations complete, WorkflowEventHandler aggregates outputs
     *   and triggers this outbox with the aggregated results
     * - We complete the listen task with the aggregated foreach outputs
     *
     * ## Strategy Detection
     *
     * Uses explicit strategy enum:
     * - ONE/ANY: Single event, immediate completion
     * - ANY_UNTIL_EXPR/ANY_UNTIL_EVENT/ALL: Accumulate events
     */
    override suspend fun process(entity: ListenerModel) {
        val eventJson = requireNotNull(entity.event) {
            "Listener ${entity.id} has no event but was picked up for completion processing"
        }

        // Parse the stored event(s)
        val parsedEvent = Json.parseToJsonElement(eventJson)

        // ONE and ANY are simple strategies (single event, immediate completion)
        // ANY_UNTIL_EXPR, ANY_UNTIL_EVENT, and ALL accumulate events
        val isSimpleStrategy = entity.strategy == ListenerStrategy.ONE ||
            entity.strategy == ListenerStrategy.ANY

        // For ONE/ANY with foreach: resume to foreach.do instead of completing
        // For ALL/ANY+until or no foreach: complete the listen task normally
        val listenPosition = entity.instanceMessage.workflowState.nodePosition
        val resumeCommand = if (entity.hasForeach && isSimpleStrategy) {
            // Resume to foreach.do position with the event as input
            // Event is stored as array (spec requirement), extract first element for foreach iteration
            val foreachPosition = listenPosition.addToken(Token.FOR)
            val eventForIteration = (parsedEvent as JsonArray).first()
            logger.debug { "Foreach enabled for ONE/ANY - resuming to foreach.do at $foreachPosition with input: $eventForIteration" }

            WorkflowCommand.ResumeFromTask(
                nodeStack = entity.instanceMessage.workflowState.nodeStack,
                nodePosition = foreachPosition,
                rawInput = eventForIteration
            )
        } else {
            // Normal completion: complete the listen task with event(s) as output
            WorkflowCommand.ResumeWithCompletedTask(
                nodeStack = entity.instanceMessage.workflowState.nodeStack,
                rawOutput = parsedEvent
            )
        }

        val resumeMessage = InstanceMessage(
            workflowInfo = entity.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        // Derive idempotent message ID from listener ID
        val messageId = entity.id.derive("-listen-complete")

        instanceEmitter.send(resumeMessage, messageId)

        logger.info {
            "Resume command sent for listener" +
                if (entity.hasForeach && isSimpleStrategy) " (foreach.do)" else " (completion)" +
                    entity
        }

        // Mark the wait model to be cleaned up
        entity.cleanupAfter = Clock.System.now()
    }
}
