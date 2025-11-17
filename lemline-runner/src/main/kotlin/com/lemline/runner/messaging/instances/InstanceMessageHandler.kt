// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.instances

import com.lemline.common.logger.logger
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.WorkflowState
import com.lemline.runner.failures.FailureReasons.DEFINITION_MISSING
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.ILLEGAL_STATE_FAILURE
import com.lemline.runner.failures.FailureReasons.SERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.WORKFLOW_EXECUTION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.database.DatabaseMessage
import com.lemline.runner.messaging.database.DatabaseMessageEmitter
import com.lemline.runner.messaging.database.createDeserializationFailure
import com.lemline.runner.messaging.database.toInfrastructureFailure
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.ForkCompletionResult
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ForkRepository
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly

/**
 * MessageHandler is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel. It orchestrates
 * the entire lifecycle of a message.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
internal class InstanceMessageHandler(
    private val instanceEmitter: InstanceMessageEmitter,
    private val databaseEmitter: DatabaseMessageEmitter,
    private val definitionRepository: DefinitionRepository,
    private val forkRepository: ForkRepository,
    override val metrics: InstanceMessageSubscriberMetrics,
) : MessageHandler<InstanceMessage> {
    override var logger = logger()

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: InstanceMessage? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    // ========================================
    // Deserialization
    // ========================================

    /**
     * Deserializes the message payload. Returns the InstanceMessage
     *
     * This function is designed to throw only CompensationException with additional actions
     */
    override suspend fun Message<String>.deserialize(): InstanceMessage = try {
        InstanceMessage.fromMessage(this)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }

        // Send deserialization failure to database channel
        throw CompensationException(DESERIALIZATION_FAILURE) {
            databaseEmitter.send(
                createDeserializationFailure(
                    payload = payload,
                    exception = e
                )
            )
        }
    }

    // ========================================
    // Serialization
    // ========================================

    /**
     * Serializes the InstanceMessage to a JSON string.
     * Can throw CompensationException for serialization errors (corrupted state).
     */
    override suspend fun serialize(current: InstanceMessage, next: InstanceMessage): String {
        return try {
            next.toJsonString()
        } catch (e: Exception) {
            logger.error(e) { "Failed to serialize message: $next" }

            // Send error to the database channel (not retryable - logic error).
            // We cannot serialize the next message
            throw CompensationException(SERIALIZATION_FAILURE) {
                databaseEmitter.send(
                    current.toInfrastructureFailure(
                        exception = e,
                        reason = SERIALIZATION_FAILURE,
                        retryable = false
                    )
                )
            }
        }
    }

    // ========================================
    //  Emission
    // ========================================

    /**
     * Emits the serialized payload to the instance message broker.
     * MessageEmitter.send() handles retries internally.
     */
    override suspend fun emit(payload: String) {
        instanceEmitter.send(payload)
    }

    // ========================================
    // Handling
    // ========================================

    /**
     * Handles the lifecycle of an incoming instance message.
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun handle(current: InstanceMessage): InstanceMessage? {
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
    private suspend fun InstanceMessage.findWorkflowDefinition(): Workflow {
        // Try cache first
        DefinitionCache.getWorkflow(
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
                ?.let { DefinitionCache.parseAndPut(it) }
        } catch (e: Exception) {
            logger.error(e) { "Error during workflow definition retrieval" }

            // Send infrastructure failure to database channel (retryable - DB might recover)
            val reason = getFailureReason(e)
            throw CompensationException(reason) {
                databaseEmitter.send(
                    toInfrastructureFailure(
                        exception = e,
                        reason = reason,
                        retryable = true
                    )
                )
            }
        }

        if (workflow != null) return workflow

        // Still not found -> non-retryable infrastructure failure
        val errorMsg = "Workflow $workflowNamespace:$workflowName:$workflowVersion not found"
        logger.error { "$errorMsg. Storing in failure table for manual inspection." }

        val error = IllegalStateException(errorMsg)

        throw CompensationException(DEFINITION_MISSING) {
            databaseEmitter.send(
                toInfrastructureFailure(
                    exception = error,
                    reason = DEFINITION_MISSING,
                    retryable = false
                )
            )
        }
    }

    /**
     * Executes one step of the workflow using WorkflowOrchestrator.
     *
     * This method calls the functional WorkflowOrchestrator to execute one activity,
     * then pattern matches on the returned WorkflowState to determine the next action.
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage.executeStep(workflow: Workflow): InstanceMessage? = try {
        // Execute using WorkflowOrchestrator
        val nextState = WorkflowOrchestrator.resume(
            workflow = workflow,
            state = workflowState,
            executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY
        )

        // Handle the outcome
        handleWorkflowState(nextState, workflow)
    } catch (e: Exception) {
        logger.error(e) { "Failed to execute workflow step" }

        // Send infrastructure failure to database channel (not retryable - logic error)
        throw CompensationException(WORKFLOW_EXECUTION_FAILURE) {
            databaseEmitter.send(
                toInfrastructureFailure(
                    exception = e,
                    reason = WORKFLOW_EXECUTION_FAILURE,
                    retryable = false
                )
            )
        }
    }

    /**
     * Handles the different WorkflowState outcomes by pattern matching.
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage.handleWorkflowState(
        nextState: WorkflowState,
        workflow: Workflow
    ): InstanceMessage? {
        val nextInstanceMessage = copy(workflowState = nextState)
        return when (nextState) {
            is WorkflowState.ReadyForNextTask -> {
                // Check if this is a branch completion
                if (isBranchCompletion(nextState)) {
                    handleBranchCompletion(nextState)
                } else {
                    // Activity completed - return next message to be emitted
                    logger.debug { "Activity completed at ${nextState.nodePosition}" }
                    nextInstanceMessage
                }
            }

            is WorkflowState.Waiting -> {
                // Check if wait time has already been reached (optimization)
                if (nextState.waitUntil <= Clock.System.now()) {
                    logger.debug { "Wait time already reached, continuing immediately" }
                    nextInstanceMessage
                } else {
                    // Send to database for persistence
                    logger.debug { "Starting wait task, resuming at ${nextState.waitUntil}" }
                    sendToDatabase(nextInstanceMessage)
                    null  // Paused
                }
            }

            is WorkflowState.Retrying -> {
                // Check if retry time has already been reached (optimization)
                if (nextState.retryAt <= Clock.System.now()) {
                    logger.debug { "Retry time reached, retrying immediately" }
                    copy(workflowState = nextState)
                } else {
                    // Send to database for persistence
                    logger.debug { "Scheduling retry, retrying at ${nextState.retryAt}" }
                    sendToDatabase(nextInstanceMessage)
                    null  // Paused
                }
            }

            is WorkflowState.RunningChildWorkflow -> {
                // Send to database for parent storage + child creation
                logger.debug { "Starting child workflow at ${nextState.nodePosition}" }
                sendToDatabase(nextInstanceMessage)
                when (nextState.childConfig.sync) {
                    // Paused - waiting for synchronous completion
                    true -> null
                    // we continue the workflow execution right away
                    false -> nextInstanceMessage
                }
            }

            is WorkflowState.RunningFork -> {
                // Send to database for fork persistence + branch scheduling
                logger.debug {
                    "Starting fork at ${nextState.nodePosition}"
                }
                sendToDatabase(nextInstanceMessage)
                null  // Paused - waiting for branches to complete
            }

            is WorkflowState.Completed -> {
                // Only persist if parent or scheduled workflow
                logger.debug { "Workflow completed with output: ${nextState.output}" }

                // Determine if this workflow has a parent or need to be scheduled after completion
                if (parentId != null || workflow.schedule?.after != null) {
                    sendToDatabase(nextInstanceMessage)
                }
                null  // Terminal
            }

            is WorkflowState.Failed -> {
                // Send to database for failure persistence
                logger.error { "Workflow failed at ${nextState.nodePosition}: ${nextState.error}" }
                sendToDatabase(nextInstanceMessage)
                null  // Terminal
            }

            is WorkflowState.Starting -> {
                // This shouldn't happen during resume - treat as infrastructure failure
                logger.error { "Unexpected Starting state when resuming workflow" }
                databaseEmitter.send(
                    toInfrastructureFailure(
                        exception = IllegalStateException("Received Starting state during resume"),
                        reason = ILLEGAL_STATE_FAILURE,
                        retryable = false
                    )
                )
                null  // Terminal
            }
        }
    }

    private suspend fun sendToDatabase(instanceMessage: InstanceMessage) {
        databaseEmitter.send(
            DatabaseMessage.WorkflowPersistence(instanceMessage)
        )
    }

    /**
     * Detects if this is a branch returning to its fork parent by checking
     * if there's an active fork in the database at this position.
     */
    private suspend fun InstanceMessage.isBranchCompletion(state: WorkflowState.ReadyForNextTask): Boolean {
        // Check if there's a fork at this position in the database
        val fork = forkRepository.findByWorkflowIdAndPosition(
            workflowInfo.workflowId,
            state.nodePosition
        )
        return fork != null
    }

    /**
     * Handles branch completion by:
     * 1. Finding the matching branch in the fork
     * 2. Recording branch completion in database (with pessimistic locking)
     * 3. Checking if fork is complete
     * 4. If complete, assembling output and resuming parent workflow
     * 5. If not complete, waiting for more branches
     */
    private suspend fun InstanceMessage.handleBranchCompletion(
        state: WorkflowState.ReadyForNextTask
    ): InstanceMessage? {
        val forkPosition = state.nodePosition
        val branchOutput = state.rawInput

        // Get fork by workflow ID and position
        val fork = forkRepository.findByWorkflowIdAndPosition(workflowInfo.workflowId, forkPosition)
            ?: throw IllegalStateException("Fork not found at $forkPosition")

        // Get fork branches to find which branch this is
        val branches = forkRepository.getBranches(fork.id)

        // Find the branch that is still pending or running
        // In a distributed system, we need to identify which branch completed based on the nodePosition
        // Since all branches eventually return to the fork position, we need another way to identify them
        // For now, we'll just take the first pending branch (this is a simplification)
        val branch = branches.firstOrNull { it.status == com.lemline.core.states.BranchStatus.PENDING }
            ?: throw IllegalStateException("No pending branch found for fork at $forkPosition")

        logger.debug {
            "Branch ${branch.branchIndex} (${branch.branchName}) completed for fork ${fork.id} at $forkPosition, " +
                "output: ${branchOutput.toString().take(100)}"
        }

        // Record branch completion and check if fork is complete
        val completionResult = forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = branch.branchIndex,
            branchOutput = branchOutput
        )

        // Check if fork is complete
        if (completionResult.isComplete) {
            logger.debug {
                "Fork complete at $forkPosition: ${completionResult.completedCount}/${completionResult.branchCount} branches, " +
                    "resuming parent workflow"
            }
            return resumeForkParent(completionResult, forkPosition)
        } else {
            logger.debug {
                "Fork not complete at $forkPosition: ${completionResult.completedCount}/${completionResult.branchCount} branches done"
            }
            return null  // Waiting for more branches
        }
    }

    /**
     * Resume parent workflow after fork completes.
     * Assembles output from completed branches and creates resume message.
     */
    private suspend fun InstanceMessage.resumeForkParent(
        completionResult: ForkCompletionResult,
        forkPosition: com.lemline.core.nodes.NodePosition
    ): InstanceMessage {
        // Assemble output based on compete mode
        val assembledOutput = if (completionResult.compete) {
            // Compete mode: return first completed branch output
            completionResult.branches
                .firstOrNull { it.status == com.lemline.core.states.BranchStatus.COMPLETED }
                ?.output
                ?.let { com.lemline.common.json.LemlineJson.decodeFromString<kotlinx.serialization.json.JsonElement>(it) }
                ?: throw IllegalStateException("No completed branch found in compete mode")
        } else {
            // Cooperative mode: return array in order
            val outputs = (0 until completionResult.branchCount).map { index ->
                val branch = completionResult.branches.find { it.branchIndex == index }
                    ?: throw IllegalStateException("Branch $index not found")

                require(branch.status == com.lemline.core.states.BranchStatus.COMPLETED) {
                    "Branch $index not completed: ${branch.status}"
                }

                com.lemline.common.json.LemlineJson.decodeFromString<kotlinx.serialization.json.JsonElement>(
                    branch.output!!
                )
            }
            kotlinx.serialization.json.JsonArray(outputs)
        }

        // Create resume message
        val resumeMessage = copy(
            workflowState = WorkflowState.ReadyForNextTask(
                taskStates = completionResult.taskStates,
                nodePosition = forkPosition,
                rawInput = assembledOutput,
                flowDirective = null
            )
        )

        // Clean up fork state - need to get fork ID first
        val fork = forkRepository.findByWorkflowIdAndPosition(workflowInfo.workflowId, forkPosition)
        if (fork != null) {
            forkRepository.delete(fork.id)
            logger.debug { "Fork parent resumed at $forkPosition, fork state deleted" }
        }

        return resumeMessage
    }
}
