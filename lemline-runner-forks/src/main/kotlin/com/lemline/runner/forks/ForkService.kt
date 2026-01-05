// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.nodes.Node
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache.getWorkflow
import com.lemline.core.workflows.branches
import com.lemline.core.workflows.getNode
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import io.serverlessworkflow.api.types.ForkTask
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Service for handling fork-related workflow events.
 *
 * Provides business logic for:
 * - Starting forks (creating fork metadata and branch messages)
 * - Handling branch completion (assembling output, resuming parent)
 * - Handling branch failure (applying compete/cooperative error semantics)
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class ForkService {

    @Inject
    lateinit var forkRepository: ForkRepository

    @Inject
    lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    private val logger = logger()

    /**
     * Handles fork started by:
     * 1. Persisting fork metadata and parent workflow state
     * 2. Creating branch models
     * 3. Emitting instance messages for each branch
     *
     * Fork configuration (compete, branches) is derived from the workflow definition node.
     *
     * @return true if the fork was created, false if it already existed (idempotent)
     */
    suspend fun handleForkStarted(instance: InstanceMessage<WorkflowEvent.ForkStarted>): Boolean {
        val forkStarted = instance.workflowState

        // Get fork node from workflow definition to extract fork configuration
        val workflowInfo = instance.workflowInfo
        val workflow = workflowInfo.getWorkflow()
            ?: error("Workflow definition not found: ${workflowInfo.namespace}/${workflowInfo.name}/${workflowInfo.version}")

        @Suppress("UNCHECKED_CAST")
        val forkNode = workflow.getNode(forkStarted.nodePosition) as Node<ForkTask>
        val isCompete = forkNode.task.fork.isCompete
        val branches = forkNode.branches

        // 1. Idempotent creation of fork model
        val forkModel = ForkModel(
            instanceMessage = instance,
            compete = isCompete
        )

        // 2. Create branch models
        val forkBranchModels = branches.map { branchNode ->
            ForkBranchModel(
                forkId = forkModel.id,
                name = branchNode.position.toString()
            )
        }

        // 3. Insert fork and branches atomically
        val rowsInserted = forkRepository.insertForkWithBranches(forkModel, forkBranchModels)
        if (rowsInserted == 0) {
            logger.warn { "Fork model $forkModel already exists (idempotent insert)" }
            return false
        }

        logger.debug {
            "Fork started for instance ${instance.workflowId}, position ${forkStarted.nodePosition}, " +
                "compete=$isCompete, branches=${branches.size}"
        }

        // 4. Emit instance messages for each branch with idempotent message IDs
        branches.forEach { branchNode ->
            val branchMessage = InstanceMessage(
                workflowInfo = instance.workflowInfo,
                workflowState = WorkflowCommand.ResumeFromTask(
                    nodeStack = instance.workflowState.nodeStack,
                    nodePosition = branchNode.position,
                    rawInput = instance.workflowState.rawInput
                ),
            )

            // Derive message ID using branch name
            val branchMessageId = forkModel.id.derive("-branch-${branchNode.position}")

            logger.debug { "Scheduling branch at ${branchNode.position}: $branchMessageId" }
            commandEmitter.send(branchMessage, branchMessageId)
        }

        return true
    }

    /**
     * Handles fork branch completion by:
     * 1. Finding the fork and identifying which branch completed
     * 2. Recording branch completion in database (with pessimistic locking)
     * 3. Checking if fork is complete
     * 4. If complete, assembling output and resuming parent workflow
     * 5. If not complete, waiting for more branches
     */
    suspend fun handleBranchCompleted(instance: InstanceMessage<WorkflowEvent.ForkBranchCompleted>) {
        val forkBranchCompleted = instance.workflowState
        val forkPosition = forkBranchCompleted.nodePosition
        val branchOutput = forkBranchCompleted.output
        val branchName = forkBranchCompleted.branchPosition

        databaseConfig.withTransaction { conn ->
            // Get fork with branches by workflow ID and position (single query)
            val (fork, branches) = forkRepository.findByWorkflowIdAndPositionWithBranches(
                instance.workflowId,
                forkPosition,
                conn
            ) ?: error("Fork not found at $forkPosition for workflow ${instance.workflowId}")

            logger.debug {
                "Branch '$branchName' completed for fork ${fork.id} at $forkPosition, " +
                    "output: ${branchOutput.toString().take(100)}"
            }

            // Find the branch by name
            val branch = branches.firstOrNull { it.name == branchName }
                ?: error("Branch '$branchName' not found in fork at $forkPosition")

            if (branch.completedAt != null) {
                logger.info { "Weird, the branch '$branchName' at $forkPosition is already completed" }
                return@withTransaction null
            }

            // Update branch with completion data
            branch.output = LemlineJson.encodeToString(branchOutput)
            branch.completedAt = Clock.System.now()
            // Clean error data if branch was previously failed
            branch.failedAt = null
            branch.errorReason = null
            branch.errorClass = null
            branch.errorMessage = null
            branch.errorStackTrace = null

            // Save the updated branch in the transaction
            forkRepository.updateBranch(branch, conn)

            // Apply business logic: check if the fork is complete based on the compete mode
            if (fork.completedAt == null) {
                val completedCount = branches.count { it.completedAt != null }
                val outputJson = when {
                    fork.compete && completedCount == 1 -> branchOutput
                    !fork.compete && completedCount == branches.size -> {
                        val outputs = branches.map { b ->
                            val out = b.output ?: error("Branch '${b.name}' has no output")
                            LemlineJson.decodeFromString<JsonElement>(out)
                        }
                        JsonArray(outputs)
                    }

                    else -> null
                }

                // Is the fork now completed?
                if (outputJson != null) {
                    logger.debug { "Fork completed at $forkPosition with output $outputJson, resuming parent workflow" }
                    // Update fork with completion data
                    val now = Clock.System.now()
                    fork.output = LemlineJson.encodeToString(outputJson)
                    fork.completedAt = now
                    fork.cleanupAfter = now
                    // Clean error data if fork was previously failed
                    fork.failedAt = null
                    fork.errorReason = null
                    fork.errorClass = null
                    fork.errorMessage = null
                    fork.errorStackTrace = null
                    forkRepository.update(fork, conn)

                    val resumeMessage = InstanceMessage(
                        workflowInfo = instance.workflowInfo,
                        workflowState = WorkflowCommand.ResumeWithCompletedTask(
                            nodeStack = instance.workflowState.nodeStack,
                            rawOutput = outputJson,
                        ),
                    )

                    // Derive resume message ID from fork model ID
                    val resumeMessageId = fork.id.derive("-resume-completed")

                    // Emit resume message to workflow channel
                    commandEmitter.send(resumeMessage, resumeMessageId)
                } else {
                    logger.debug { "Fork not yet completed at $forkPosition" }
                    // Waiting for more branches - nothing to do
                }
            }
        }
    }

    /**
     * Handles fork branch failure by:
     * 1. Finding the fork and identifying which branch failed
     * 2. Recording branch failure in database (with pessimistic locking)
     * 3. Applying compete/cooperative error semantics:
     *    - compete=true: Wait for all branches to finish, only fail if all failed
     *    - compete=false: Fail fork immediately on first branch failure
     * 4. If fork should fail, resuming parent workflow with error
     */
    suspend fun handleBranchFailed(instance: InstanceMessage<WorkflowEvent.ForkBranchFailed>) {
        val state = instance.workflowState
        val forkPosition = state.nodePosition
        val branchError = state.error
        val branchName = state.branchPosition

        databaseConfig.withTransaction { conn ->
            // Get fork with branches by workflow ID and position (single query)
            val (fork, branches) = forkRepository.findByWorkflowIdAndPositionWithBranches(
                instance.workflowId,
                forkPosition,
                conn
            ) ?: error("Fork not found at $forkPosition for workflow ${instance.workflowId}")

            logger.debug {
                "Branch '$branchName' failed for fork ${fork.id} at $forkPosition, " +
                    "error: ${branchError.title ?: branchError.type}"
            }

            // Find the branch by name
            val branch = branches.firstOrNull { it.name == branchName }
                ?: error("Branch '$branchName' not found in fork at $forkPosition")

            if (branch.failedAt != null) {
                logger.info { "Weird, the branch '$branchName' at $forkPosition is already failed" }
                return@withTransaction null
            }
            if (branch.completedAt != null) {
                logger.info { "Weird, the branch '$branchName' at $forkPosition is already completed" }
                return@withTransaction null
            }

            // Update branch with failure data
            branch.failedAt = Clock.System.now()
            branch.errorReason = branchError.type
            branch.errorClass = branchError.type
            branch.errorMessage = branchError.title
            branch.errorStackTrace = branchError.details

            // Save the updated branch in the transaction
            forkRepository.updateBranch(branch, conn)

            // Apply business logic based on compete mode
            if (fork.completedAt == null && fork.failedAt == null) {

                val failedCount = branches.count { it.failedAt != null }
                val error = when {
                    fork.compete && failedCount == branches.size -> instance.workflowState.error
                    !fork.compete && failedCount == 1 -> instance.workflowState.error
                    else -> null
                }

                if (error != null) {
                    logger.debug { "Fork failed at $forkPosition, resuming workflow with error" }

                    fork.failedAt = branch.failedAt
                    fork.errorReason = branchError.type
                    fork.errorClass = branchError.type
                    fork.errorMessage = branchError.title
                    fork.errorStackTrace = branchError.details

                    // Save the updated fork in the transaction
                    forkRepository.update(fork, conn)

                    val resumeMessage = InstanceMessage(
                        workflowInfo = instance.workflowInfo,
                        workflowState = WorkflowCommand.ResumeWithFailedTask(
                            nodeStack = instance.workflowState.nodeStack,
                            error = error,
                        ),
                    )

                    // Derive resume message ID from fork model ID
                    val resumeMessageId = fork.id.derive("-resume-failed")
                    commandEmitter.send(resumeMessage, resumeMessageId)
                }
            }
        }
    }
}
