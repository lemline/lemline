// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.info
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.cloudevents.CloudEventFactory
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.getNode
import com.lemline.core.workflows.useFunctions
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.definitions.DefinitionRepository
import com.lemline.runner.failures.FailureModel
import com.lemline.runner.failures.FailureReasons.DEFINITION_MISSING
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.SERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.failures.FailureRepository
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.activities.functions.FunctionResolver
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.cloudevents.CloudEventsEmitter
import com.lemline.runner.messaging.events.WorkflowEventEmitter
import com.lemline.runner.messaging.toLogString
import io.serverlessworkflow.api.types.Task
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly

/**
 * Handles workflow commands by executing workflow steps and emitting resulting events.
 *
 * Processes InstanceMessage<WorkflowCommand> from the workflow channel, executes one step
 * using WorkflowOrchestrator, and either:
 * - Emits next command for continued execution (TaskScheduled)
 * - Sends event to database channel for persistence (WaitStarted, RetryScheduled, etc.)
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
internal class WorkflowCommandHandler(
    private val commandEmitter: WorkflowCommandEmitter,
    private val eventEmitter: WorkflowEventEmitter,
    private val definitionRepository: DefinitionRepository,
    private val failureRepository: FailureRepository,
    override val metrics: WorkflowCommandSubscriberMetrics,
    private val config: LemlineConfiguration,
    private val activityExecutor: ActivityExecutor,
    private val functionResolver: FunctionResolver,
    private val lifecycleHook: LifecycleEventHook,
    private val cloudEventsEmitter: Instance<CloudEventsEmitter>,
) : MessageHandler<InstanceMessage<WorkflowCommand>> {
    override var logger = logger()

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: InstanceMessage<WorkflowCommand>? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    /**
     * Test callback invoked when a terminal workflow event is produced.
     * Used by broker tests to capture completion without relying on the events channel.
     */
    @TestOnly
    var onEventProducedTest: (InstanceMessage<WorkflowCommand>, WorkflowEvent) -> Unit = { _, _ -> }

    // ========================================
    // Deserialization
    // ========================================

    /**
     * Deserializes the message payload. Returns the InstanceMessage
     *
     * This function is designed to throw only CompensationException with additional actions
     */
    override suspend fun Message<String>.deserialize(): InstanceMessage<WorkflowCommand> = try {
        InstanceMessage.fromMessage(this)
    } catch (e: Exception) {
        logger.warn { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }

        // Store deserialization failure directly (we don't have a valid InstanceMessage to work with)
        throw CompensationException(DESERIALIZATION_FAILURE) {
            deserializationFailed(e)
        }
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) {
        val failure = FailureModel.from(
            id = IDV7.random(),
            payload = payload,
            exception = cause,
            reason = DESERIALIZATION_FAILURE
        )
        failureRepository.insert(failure)
    }

    // ========================================
    // Serialization
    // ========================================

    /**
     * Serializes the InstanceMessage to a JSON string.
     * Can throw CompensationException for serialization errors (corrupted state).
     */
    override suspend fun serialize(
        current: InstanceMessage<WorkflowCommand>,
        next: InstanceMessage<WorkflowCommand>
    ): String {
        return try {
            next.toJsonString()
        } catch (e: Exception) {
            logger.error(e) { "Failed to serialize message: $next" }

            // Send TaskFailed event to database (not retryable - serialization is a permanent error)
            throw CompensationException(SERIALIZATION_FAILURE) {
                eventEmitter.send(
                    current.toWorkflowFailed(
                        exception = e,
                        reason = SERIALIZATION_FAILURE
                    )
                )
            }
        }
    }

    /**
     * Converts an InstanceMessage<WorkflowCommand> to InstanceMessage<WorkflowEvent>
     * for infrastructure failures that should be stored as permanent failures.
     */
    private fun InstanceMessage<WorkflowCommand>.toWorkflowFailed(
        exception: Exception,
        reason: String
    ): InstanceMessage<WorkflowEvent> {
        val error = InternalException.Error(
            type = exception::class.qualifiedName ?: "Unknown",
            status = 500,
            position = workflowId.toString(),
            title = exception.message,
            details = exception.stackTraceToString()
        )

        return InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = WorkflowEvent.WorkflowFailed(
                nodeStack = workflowState.nodeStack,
                rawInput = null,
                rawOutput = null,
                flowDirective = null,
                error = error,
                failedAt = Clock.System.now()
            ),
        )
    }

    // ========================================
    //  Emission
    // ========================================

    /**
     * Emits the serialized payload to the instance message broker.
     * MessageEmitter.send() handles retries internally.
     *
     * @param payload The serialized message payload
     * @param idempotentKey Optional deterministic ID for message deduplication
     */
    override suspend fun emit(payload: String, idempotentKey: IDV7) {
        commandEmitter.sendPayload(payload, idempotentKey)
    }

    /**
     * Derives an idempotent key for command messages.
     * Uses position + step to ensure deterministic IDs across redeliveries.
     */
    override fun deriveIdempotentKey(next: InstanceMessage<WorkflowCommand>): IDV7 =
        next.workflowState.nodeStack.deriveIdempotentId("-command")

    // ========================================
    // Handling
    // ========================================

    /**
     * Handles the lifecycle of an incoming instance message.
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun handle(current: InstanceMessage<WorkflowCommand>): InstanceMessage<WorkflowCommand>? {
        // --- Get Workflow Definition ---
        val workflow = current.findWorkflowDefinition()
        // --- Execute step using WorkflowOrchestrator ---
        return current.executeStep(workflow)
    }

    /**
     * Retrieves a workflow definition based on the provided name and version.
     *
     * This method first attempts to fetch the workflow from a cache. If not found,
     * it retrieves the definition from a repository, parses it, and stores it in the cache.
     *
     * If the workflow is still not found, the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage<WorkflowCommand>.findWorkflowDefinition(): Workflow {
        // Try cache first
        WorkflowCache.getWorkflow(
            namespace = workflowNamespace,
            name = workflowName,
            version = workflowVersion
        )?.let { return it }

        // Fallback to repository, with error handling
        val workflow = try {
            definitionRepository.findByNameAndVersion(
                workflowNamespace,
                workflowName,
                workflowVersion
            )
                ?.definition
                ?.let { WorkflowCache.parseYamlAndPut(it) }
        } catch (e: Exception) {
            logger.error(e) { "Error during workflow definition retrieval" }

            // Send TaskFailed event to database (broker will handle message-level retries)
            val reason = getFailureReason(e)
            throw CompensationException(reason) {
                eventEmitter.send(
                    toWorkflowFailed(
                        exception = e,
                        reason = reason
                    )
                )
            }
        }

        if (workflow != null) return workflow

        // Still not found -> permanent failure
        val errorMsg = "Definition not found for workflow $workflowNamespace/$workflowName/$workflowVersion"
        logger.error { "$errorMsg. Storing in failure table for manual inspection." }

        val error = IllegalStateException(errorMsg)

        throw CompensationException(DEFINITION_MISSING) {
            eventEmitter.send(
                toWorkflowFailed(
                    exception = error,
                    reason = DEFINITION_MISSING
                )
            )
        }
    }

    /**
     * Executes one step of the workflow using WorkflowOrchestrator.
     *
     * This method calls the functional WorkflowOrchestrator to execute one step,
     * then pattern matches on the returned WorkflowState to determine the next action.
     *
     * The step granularity is controlled by [LemlineConfiguration.OrchestratorConfig.mode]:
     * - ACTION: Batches control flow nodes, emits message only for action tasks (default)
     * - ALL: Emits a message for every task including control flow nodes
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage<WorkflowCommand>.executeStep(workflow: Workflow): InstanceMessage<WorkflowCommand>? {
        // Execute using StepByStepOrchestrator
        logger.debug { "resumeFromTask state=$workflowState" }

        // Create resolver lambda for remote functions (URLs, catalogs)
        val resolverLambda: suspend (String) -> Task? = { ref ->
            try {
                functionResolver.resolve(ref)
            } catch (_: Exception) {
                null
            }
        }

        val event = when (config.orchestrator().mode()) {
            LemlineConfiguration.OrchestratorMode.ALL -> StepByStepOrchestrator.runByTask(
                workflow,
                workflowState,
                workflowInfo,
                lifecycleHook,
                resolverLambda,
            )

            LemlineConfiguration.OrchestratorMode.ACTION -> StepByStepOrchestrator.runByActivity(
                workflow,
                workflowState,
                workflowInfo,
                lifecycleHook,
                resolverLambda,
            )
        }

        // Handle the outcome
        return handleEvent(workflow, event)
    }

    /**
     * Handles the different WorkflowState outcomes by pattern matching.
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage<WorkflowCommand>.handleEvent(
        workflow: Workflow,
        event: WorkflowEvent
    ): InstanceMessage<WorkflowCommand>? {
        return when (event) {
            is WorkflowEvent.TaskScheduled -> {
                // Activity scheduled
                logger.debug { "Activity scheduled node=${event.nodePosition} - ${workflow.getNode(event.nodePosition).task::class.simpleName}(input=${event.rawInput})" }
                event.resume()
            }

            is WorkflowEvent.WaitStarted -> {
                // Check if the wait time has already been reached (optimization)
                if (event.config.waitUntil <= Clock.System.now()) {
                    logger.debug { "Wait time already reached, continuing immediately" }
                    event.resume()
                } else {
                    // Send to the database for persistence
                    sendToEventChannel(this, event)
                    null  // Paused
                }
            }

            is WorkflowEvent.TaskRetryScheduled -> {
                // Check if the retry time has already been reached
                if (event.retryAt <= Clock.System.now()) {
                    logger.debug { "Retry time reached, retrying immediately" }
                    event.resume()
                } else {
                    // Send to the database for persistence
                    sendToEventChannel(this, event)
                    null  // Paused
                }
            }

            is WorkflowEvent.RunWorkflowStarted -> {
                // Send to the database for parent storage + child creation
                sendToEventChannel(this, event)

                when (event.config.sync) {
                    // waiting for synchronous completion
                    true -> null
                    // Not waiting for sub-workflow completion
                    false -> event.resumeAsync()
                }
            }

            is WorkflowEvent.ForkStarted -> {
                // Send to the database for fork persistence + branch scheduling
                sendToEventChannel(this, event)
                null  // Paused - waiting for branches to complete
            }

            is WorkflowEvent.EmitStarted -> {
                // Emit CloudEvent and resume immediately (fire-and-forget)
                emitCloudEvent(this, event)
                event.resume()
            }

            is WorkflowEvent.ListenStarted -> {
                // Send to the database for listener persistence
                // The listener will be stored and CloudEvents will be matched against it
                sendToEventChannel(this, event)
                null  // Paused - waiting for matching CloudEvents
            }

            is WorkflowEvent.ActivityStarted -> {
                // Execute the activity and resume with the result
                executeActivity(event)
            }

            // CallFunctionStarted is no longer emitted - CallFunction is now a control-flow task
            // that navigates to function nodes step-by-step through normal message flow

            is WorkflowEvent.WorkflowCompleted -> {
                // Only persist if parent or scheduled workflow
                logger.debug { "Workflow completed with output: ${event.output}" }

                // Notify test callback (before potentially sending to database)
                onEventProducedTest(this, event)

                // Determine if this workflow has a parent or need to be scheduled after completion
                if (event.hasWaitingParent || workflow.schedule?.after != null) {
                    sendToEventChannel(this, event)
                }
                null  // Terminal
            }

            is WorkflowEvent.WorkflowFailed -> {
                // Notify test callback (before sending to database)
                onEventProducedTest(this, event)

                // Send to database for failure persistence
                sendToEventChannel(this, event)
                null  // Terminal
            }

            is WorkflowEvent.ForkBranchCompleted -> {
                // Send to database for branch completion tracking
                sendToEventChannel(this, event)
                null  // Terminal
            }

            is WorkflowEvent.ForkBranchFailed -> {
                // Send to database for branch failure tracking
                sendToEventChannel(this, event)
                null  // Terminal
            }

            is WorkflowEvent.ForEachCompleted -> {
                // Send to database for foreach iteration completion handling
                // The database handler will check for next event or complete the listener
                sendToEventChannel(this, event)
                null  // Terminal
            }
        }?.let {
            copy(workflowState = it)
        }
    }

    /**
     * Sends a workflow event to the database channel for persistence.
     */
    private suspend fun sendToEventChannel(message: InstanceMessage<WorkflowCommand>, event: WorkflowEvent) {
        logger.debug { "Sending to events channel: $event" }
        eventEmitter.send(
            InstanceMessage(
                workflowInfo = message.workflowInfo,
                workflowState = event,
            )
        )
    }

    /**
     * Executes an activity via the ActivityExecutor and returns the resume command.
     *
     * Activities include HTTP calls, script execution, shell commands, and CloudEvent emission.
     * On success, resumes with the activity output. On failure, resumes with the error.
     */
    private suspend fun executeActivity(event: WorkflowEvent.ActivityStarted): WorkflowCommand {
        logger.debug { "Executing activity: ${event::class.simpleName} at ${event.nodePosition}" }
        return try {
            val output = activityExecutor.execute(event)
            logger.debug { "Activity completed: ${event::class.simpleName}" }
            event.resumeCompleted(output)
        } catch (e: Exception) {
            logger.error(e) { "Activity failed: ${event::class.simpleName}" }
            event.resumeFailed(InternalException.Error.from(e, event.nodePosition))
        }
    }

    // executeFunction and executeSyntheticWorkflow removed - CallFunction is now a control-flow task
    // that navigates to function nodes step-by-step through normal message flow

    /**
     * Emits a CloudEvent based on EmitStarted event configuration.
     *
     * This is fire-and-forget: errors are logged but don't fail the workflow.
     * If CloudEventsEmitter is not available (disabled), the event is silently skipped.
     */
    private suspend fun emitCloudEvent(message: InstanceMessage<WorkflowCommand>, event: WorkflowEvent.EmitStarted) {
        if (!cloudEventsEmitter.isResolvable) {
            logger.debug { "CloudEventsEmitter not available, skipping emit task" }
            return
        }

        val cloudEvent = CloudEventFactory.build(event.config)
        logger.debug { "Emitting CloudEvent: type=${event.config.type} source=${event.config.source}" }

        cloudEventsEmitter.get().send(
            cloudEvent = cloudEvent,
            workflowId = message.workflowId.toString(),
            workflowNamespace = message.workflowNamespace.toString(),
            workflowName = message.workflowName.toString(),
            workflowVersion = message.workflowVersion.toString()
        )
    }
}
