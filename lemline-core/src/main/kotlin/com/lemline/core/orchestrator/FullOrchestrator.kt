// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.definitions.getNode
import com.lemline.core.errors.InternalException
import com.lemline.core.nodes.Node
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.utils.mapAwaitAllFailFast
import com.lemline.core.utils.mapAwaitFirstFailSlow
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Handles execution of fork tasks (parallel branch execution).
 *
 * This executor manages both compete (race) and cooperative (all) fork modes,
 * coordinating parallel execution of multiple workflow branches.
 */
@ExperimentalTime
internal object FullOrchestrator {

    private val logger = logger()

    suspend fun start(
        workflow: Workflow,
        workflowId: WorkflowId = WorkflowId.random(),
        workflowInput: JsonElement = buildJsonObject { },
        hasWaitingParent: Boolean = false,
        startedAt: Instant = Clock.System.now(),
        serde: Boolean = false
    ): JsonElement {
        val cmd = StepByStepOrchestrator.initCmd(workflowId, workflowInput, hasWaitingParent, startedAt)

        return resume(workflow, cmd, serde).value()
    }

    suspend fun resume(
        workflow: Workflow,
        command: WorkflowCommand,
        serde: Boolean,
    ): WorkflowEvent.Outcome {

        val serdeCommand = when (serde) {
            true -> WorkflowState.fromJsonString(command.toJsonString()) as WorkflowCommand
            false -> command
        }

        if (command != serdeCommand)
            throw IllegalStateException("Command mismatch\ncommand     : $command\nserdeCommand: $serdeCommand")

        val event = StepByStepOrchestrator.runByTask(workflow, serdeCommand)

        val serdeEvent = when (serde) {
            true -> WorkflowState.fromJsonString(event.toJsonString()) as WorkflowEvent
            false -> event
        }

        if (event != serdeEvent)
            throw IllegalStateException("Event mismatch\nevent     : $event\nserdeEvent: $serdeEvent")

        return when (serdeEvent) {
            is WorkflowEvent.WaitStarted -> resume(workflow, handle(serdeEvent), serde)
            is WorkflowEvent.TaskScheduled -> resume(workflow, handle(serdeEvent), serde)
            is WorkflowEvent.TaskRetryScheduled -> resume(workflow, handle(serdeEvent), serde)
            is WorkflowEvent.RunWorkflowStarted -> resume(workflow, handle(serdeEvent, serde), serde)
            is WorkflowEvent.ForkStarted -> resume(workflow, handle(workflow, serdeEvent, serde), serde)
            is WorkflowEvent.Outcome -> serdeEvent
        }
    }

    /**
     * Handles a retry event by delaying execution until the scheduled retry time
     * and then resuming the workflow from the specified task.
     */
    private suspend fun handle(event: WorkflowEvent.TaskRetryScheduled): WorkflowCommand {
        val delayDuration = event.retryAt - Clock.System.now()
        logger.debug { "Retrying in $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Retrying" }
        return event.resume()
    }

    /**
     * Handles a `RunWorkflowStarted` event by initiating the corresponding child workflow either
     * synchronously or asynchronously, depending on its configuration.
     */
    private suspend fun handle(event: WorkflowEvent.RunWorkflowStarted, serde: Boolean): WorkflowCommand {
        // Retrieve child workflow definition
        val childWorkflow = DefinitionCache.getWorkflow(
            namespace = event.childConfig.namespace,
            name = event.childConfig.name,
            version = event.childConfig.version
        ) ?: throw IllegalStateException("Workflow definition not found")

        return when (event.childConfig.sync) {
            true -> {
                // synchronous execution
                val initCmd = StepByStepOrchestrator.initCmd(
                    workflowInput = event.childConfig.input,
                    hasWaitingParent = true
                )
                val result = resume(childWorkflow, initCmd, serde)
                logger.debug { "Child workflow completed" }
                when (result) {
                    is WorkflowEvent.WorkflowCompleted -> event.resumeAsCompleted(result.output)
                    is WorkflowEvent.ForkBranchCompleted -> event.resumeAsCompleted(result.output)
                    is WorkflowEvent.WorkflowFailed -> event.resumeAsFailed(result.error)
                    is WorkflowEvent.ForkBranchFailed -> event.resumeAsFailed(result.error)
                }
            }

            false -> {
                // asynchronous execution
                CoroutineScope(currentCoroutineContext()).launch {
                    val initCmd = StepByStepOrchestrator.initCmd(
                        workflowInput = event.childConfig.input,
                        hasWaitingParent = false
                    )
                    resume(childWorkflow, initCmd, serde) // <= output is not handled
                    logger.debug { "Child workflow completed" }
                }
                // Immediate resuming
                event.resumeAsync()
            }
        }
    }

    /**
     * Handles the provided `TaskScheduled` event by resuming the workflow from the next task.
     */
    private fun handle(event: WorkflowEvent.TaskScheduled): WorkflowCommand = event.resume()

    /**
     * Handles the provided `WaitStarted` event by waiting until the specified time
     * and then resumes the workflow from the started task.
     */
    private suspend fun handle(event: WorkflowEvent.WaitStarted): WorkflowCommand {
        val delayDuration = event.waitUntil - Clock.System.now()
        logger.debug { "Waiting for $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Waiting completed" }

        return event.resume()
    }

    /**
     * Handles the `ForkStarted` event during workflow execution by determining the type of fork
     * operation (compete or cooperative), executing the corresponding branches, and resuming
     * the workflow with the computed output.
     */
    private suspend fun handle(workflow: Workflow, event: WorkflowEvent.ForkStarted, serde: Boolean): WorkflowCommand {
        @Suppress("UNCHECKED_CAST")
        val forkNode = workflow.getNode(event.nodePosition) as Node<ForkTask>

        val branches = forkNode.children
            ?: throw IllegalStateException("Fork node in ${forkNode.position} has no branches")

        // Execute branches and get the result
        return try {
            val output = if (forkNode.task.fork.isCompete) {
                workflow.executeCompete(event.nodeStack, branches, event.rawInput, serde)
            } else {
                workflow.executeCooperative(event.nodeStack, branches, event.rawInput, serde)
            }
            logger.debug { "Fork completed: output=$output" }
            WorkflowCommand.ResumeWithCompletedTask(
                nodeStack = event.nodeStack,
                rawOutput = output,
            )
        } catch (e: InternalException) {
            logger.error { "Fork failed: error=$e" }
            WorkflowCommand.ResumeWithFailedTask(
                nodeStack = event.nodeStack,
                error = InternalException.Error.from(e, forkNode.position)
            )
        }
    }

    /**
     * Execute fork branches in compete mode (race for first completion).
     *
     * Returns the output from the first branch to complete successfully.
     * Throws an exception if all branches fail.
     */
    private suspend fun Workflow.executeCompete(
        nodeStack: NodeStack,
        branches: List<Node<*>>,
        rawInput: JsonElement,
        serde: Boolean
    ): JsonElement {
        // Get the first success - if all branches failed, the last exception will be rethrown from here
        return branches.mapAwaitFirstFailSlow { branchNode ->
            resume(
                workflow = this,
                command = WorkflowCommand.ResumeFromTask(
                    nodeStack = nodeStack,
                    nodePosition = branchNode.position,
                    rawInput = rawInput,
                    flowDirective = null
                ),
                serde = serde
            ).value()
        }
    }

    /**
     * Execute fork branches in cooperative mode (wait for all).
     *
     * Returns an array containing outputs from all branches.
     * Throws an exception for the first branch failing.
     */
    private suspend fun Workflow.executeCooperative(
        nodeStack: NodeStack,
        branches: List<Node<*>>,
        rawInput: JsonElement,
        serde: Boolean
    ): JsonArray {
        // Get all results - If a branch failed, the first exception will be rethrown from here
        return JsonArray(
            branches.mapAwaitAllFailFast { branchNode ->
                resume(
                    workflow = this,
                    command = WorkflowCommand.ResumeFromTask(
                        nodeStack = nodeStack,
                        nodePosition = branchNode.position,
                        rawInput = rawInput,
                        flowDirective = null
                    ),
                    serde = serde
                ).value()
            })
    }
}
