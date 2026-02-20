// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.info
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.functions.FunctionResolver
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.full.CollectResult
import com.lemline.core.orchestrator.full.ForkBranchExecutor
import com.lemline.core.orchestrator.full.ListenEventCollector
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.states.protobuf.WorkflowStateProtobufMapper
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.branches
import com.lemline.core.workflows.foreachBlock
import com.lemline.core.workflows.getNode
import com.lemline.messages.internal.v1.WorkflowStateProto
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

internal class EarlyCompletionException(val event: WorkflowEvent.Outcome) : Exception()

@ExperimentalTime
object FullOrchestrator {

    private val logger = logger()

    suspend fun start(
        workflow: Workflow,
        workflowId: WorkflowId = WorkflowId.random(),
        workflowInput: JsonElement = buildJsonObject { },
        hasWaitingParent: Boolean = false,
        startedAt: Instant = Clock.System.now(),
        serde: Boolean = false,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): JsonElement {
        val cmd = StepByStepOrchestrator.initCmd(workflowId, workflowInput, hasWaitingParent, startedAt)
        return resume(
            workflow = workflow,
            command = cmd,
            serde = serde,
            activityExecutor = activityExecutor,
            functionResolver = functionResolver,
            cloudEventHook = cloudEventHook,
            lifecycleHook = lifecycleHook
        ).value()
    }

    suspend fun resume(
        workflow: Workflow,
        command: WorkflowCommand,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent.Outcome {
        val serdeCommand = validateSerde(command, serde)
        // Convert FunctionResolver to lambda for StepByStepOrchestrator
        val event = StepByStepOrchestrator.runByTask(
            workflow = workflow,
            command = serdeCommand,
            workflowInfo = workflow.info,
            lifecycleHook = lifecycleHook,
            functionResolver = functionResolver::resolve
        )
        val serdeEvent = validateSerde(event, serde)

        return handleEvent(
            serdeEvent,
            workflow,
            serde,
            activityExecutor,
            functionResolver,
            cloudEventHook,
            lifecycleHook
        )
    }

    private fun validateSerde(command: WorkflowCommand, serde: Boolean): WorkflowCommand {
        if (!serde) return command
        val commandProto = WorkflowStateProtobufMapper.toProto(command)
        val roundTripped = roundTripViaProtobuf(command)
        val roundTrippedProto = WorkflowStateProtobufMapper.toProto(roundTripped)
        if (commandProto != roundTrippedProto) {
            throw IllegalStateException(
                "Command mismatch\n" +
                    "command     : $command\n" +
                    "serdeCommand: $roundTripped\n" +
                    "commandProto     : $commandProto\n" +
                    "serdeCommandProto: $roundTrippedProto"
            )
        }
        return roundTripped
    }

    private fun validateSerde(event: WorkflowEvent, serde: Boolean): WorkflowEvent {
        if (!serde) return event
        val eventProto = WorkflowStateProtobufMapper.toProto(event)
        val roundTripped = roundTripViaProtobuf(event)
        val roundTrippedProto = WorkflowStateProtobufMapper.toProto(roundTripped)
        if (eventProto != roundTrippedProto) {
            throw IllegalStateException(
                "Event mismatch\n" +
                    "event     : $event\n" +
                    "serdeEvent: $roundTripped\n" +
                    "eventProto     : $eventProto\n" +
                    "serdeEventProto: $roundTrippedProto"
            )
        }
        return roundTripped
    }

    private inline fun <reified S : WorkflowState> roundTripViaProtobuf(state: S): S {
        val proto = WorkflowStateProtobufMapper.toProto(state)
        val decoded = WorkflowStateProtobufMapper.fromProto(
            WorkflowStateProto.ADAPTER.decode(WorkflowStateProto.ADAPTER.encode(proto))
        )
        require(decoded is S) {
            "Protobuf round-trip type mismatch. Expected ${S::class.qualifiedName}, got ${decoded::class.qualifiedName}"
        }
        return decoded
    }

    private suspend fun handleEvent(
        event: WorkflowEvent,
        workflow: Workflow,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent.Outcome = when (event) {
        // All activities (HTTP, Script, Shell, Emit) go through ActivityExecutor
        is WorkflowEvent.ActivityStarted -> resumeWith(
            workflow, handleActivityStarted(event, activityExecutor),
            serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.WaitStarted -> resumeWith(
            workflow, handleWaitStarted(event),
            serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.TaskScheduled -> resumeWith(
            workflow, event.resume(),
            serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.TaskRetryScheduled -> resumeWith(
            workflow, handleTaskRetryScheduled(event),
            serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.RunWorkflowStarted -> resumeWith(
            workflow,
            handleRunWorkflowStarted(event, serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook),
            serde,
            activityExecutor,
            functionResolver,
            cloudEventHook,
            lifecycleHook
        )

        // CallFunctionStarted is no longer emitted - CallFunction is now a control-flow task
        // that navigates to function nodes step-by-step through normal message flow

        is WorkflowEvent.ForkStarted -> resumeWith(
            workflow,
            handleForkStarted(
                workflow,
                event,
                serde,
                activityExecutor,
                functionResolver,
                cloudEventHook,
                lifecycleHook
            ),
            serde,
            activityExecutor,
            functionResolver,
            cloudEventHook,
            lifecycleHook
        )

        is WorkflowEvent.ListenStarted -> try {
            resumeWith(
                workflow,
                handleListenStarted(
                    workflow,
                    event,
                    serde,
                    activityExecutor,
                    functionResolver,
                    cloudEventHook,
                    lifecycleHook
                ),
                serde,
                activityExecutor,
                functionResolver,
                cloudEventHook,
                lifecycleHook
            )
        } catch (e: EarlyCompletionException) {
            e.event
        }

        is WorkflowEvent.Outcome -> event
    }

    private suspend fun resumeWith(
        workflow: Workflow,
        command: WorkflowCommand,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent.Outcome =
        resume(workflow, command, serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook)

    private suspend fun handleActivityStarted(
        event: WorkflowEvent.ActivityStarted,
        activityExecutor: ActivityExecutor
    ): WorkflowCommand {
        logger.debug { "Executing activity: ${event::class.simpleName}" }
        return try {
            val output = activityExecutor.execute(event)
            logger.debug { "Activity completed: ${event::class.simpleName}" }
            event.resumeCompleted(output)
        } catch (e: Exception) {
            logger.error(e) { "Activity failed: ${event::class.simpleName}" }
            event.resumeFailed(InternalException.Error.from(e, event.nodePosition))
        }
    }

    private suspend fun handleTaskRetryScheduled(event: WorkflowEvent.TaskRetryScheduled): WorkflowCommand {
        val delayDuration = event.retryAt - Clock.System.now()
        logger.debug { "Retrying in $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Retrying" }
        return event.resume()
    }

    private suspend fun handleWaitStarted(event: WorkflowEvent.WaitStarted): WorkflowCommand {
        val delayDuration = event.config.waitUntil - Clock.System.now()
        logger.debug { "Waiting for $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Waiting completed" }
        return event.resume()
    }

    private suspend fun handleListenStarted(
        workflow: Workflow,
        listenStarted: WorkflowEvent.ListenStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        logger.debug { "Listening for CloudEvents: strategy=${listenStarted.config.strategy} filters=${listenStarted.config.filters} until=${listenStarted.config.until}" }

        @Suppress("UNCHECKED_CAST")
        val listenNode = workflow.getNode(listenStarted.nodePosition, functionResolver::resolve) as Node<ListenTask>

        val foreachProcessor = listenNode.foreachBlock?.let { foreachBlock ->
            ListenEventCollector.createForeachProcessor(foreachBlock.position, foreachBlock.task) { cmd ->
                resume(workflow, cmd, serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook)
            }
        }

        val timeoutMillis = listenStarted.config.timeoutAt?.let {
            (it - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
        } ?: Long.MAX_VALUE

        return try {
            when (val result = withTimeout(timeoutMillis) {
                ListenEventCollector.collect(listenStarted, cloudEventHook.receive(), foreachProcessor)
            }) {
                is CollectResult.Success -> listenStarted.resumeCompleted(JsonArray(result.outputs))
                is CollectResult.Failure -> listenFailed(result.nodeStack, listenStarted.nodePosition, result.error)
                is CollectResult.EscapedToCompletion -> throw EarlyCompletionException(result.event)
            }
        } catch (_: TimeoutCancellationException) {
            val e = IllegalStateException("Listen timeout: no matching CloudEvent received within ${timeoutMillis}ms")
            listenFailed(listenStarted.nodeStack, listenStarted.nodePosition, e)
        }
    }

    private fun listenFailed(nodeStack: NodeStack, position: NodePosition, e: Exception): WorkflowCommand {
        logger.debug(e) { "Listen failed" }
        return WorkflowCommand.ResumeWithFailedTask(
            nodeStack = nodeStack.popUntil(position).incrementTopCounter(),
            error = InternalException.Error.from(e, position)
        )
    }

    private suspend fun handleRunWorkflowStarted(
        event: WorkflowEvent.RunWorkflowStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        val childWorkflow = WorkflowCache.getWorkflow(
            namespace = event.config.namespace,
            name = event.config.name,
            version = event.config.version
        ) ?: throw IllegalStateException("Workflow definition not found")

        return when (event.config.sync) {
            true -> {
                val initCmd = StepByStepOrchestrator.initCmd(
                    workflowInput = event.config.input,
                    hasWaitingParent = true
                )
                val result = resume(
                    childWorkflow,
                    initCmd,
                    serde,
                    activityExecutor,
                    functionResolver,
                    cloudEventHook,
                    lifecycleHook
                )
                logger.debug { "Child workflow completed" }
                when (result) {
                    is WorkflowEvent.WorkflowCompleted -> event.resumeAsCompleted(result.output)
                    is WorkflowEvent.WorkflowFailed -> event.resumeAsFailed(result.error)
                    else -> throw IllegalStateException("Child workflow returned unexpected outcome: $result")
                }
            }

            false -> {
                CoroutineScope(currentCoroutineContext()).launch {
                    val initCmd = StepByStepOrchestrator.initCmd(
                        workflowInput = event.config.input,
                        hasWaitingParent = false
                    )
                    resume(
                        childWorkflow,
                        initCmd,
                        serde,
                        activityExecutor,
                        functionResolver,
                        cloudEventHook,
                        lifecycleHook
                    )
                    logger.debug { "Child workflow completed" }
                }
                event.resumeAsync()
            }
        }
    }

    // handleCallFunctionStarted removed - CallFunction is now a control-flow task
    // that navigates to function nodes step-by-step through normal message flow

    @Suppress("UNCHECKED_CAST")
    private suspend fun handleForkStarted(
        workflow: Workflow,
        event: WorkflowEvent.ForkStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        val forkNode = workflow.getNode(event.nodePosition, functionResolver::resolve) as Node<ForkTask>
        val branches = forkNode.branches.ifEmpty {
            throw IllegalStateException("Fork node in ${forkNode.position} has no branches")
        }

        return try {
            val output = if (forkNode.task.fork.isCompete) {
                ForkBranchExecutor.executeCompete(
                    workflow, event.nodeStack, branches, event.rawInput,
                    serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook, ::resume
                )
            } else {
                ForkBranchExecutor.executeCooperative(
                    workflow, event.nodeStack, branches, event.rawInput,
                    serde, activityExecutor, functionResolver, cloudEventHook, lifecycleHook, ::resume
                )
            }
            logger.debug { "Fork completed: output=$output" }
            WorkflowCommand.ResumeWithCompletedTask(nodeStack = event.nodeStack, rawOutput = output)
        } catch (e: InternalException) {
            logger.error { "Fork failed: error=$e" }
            WorkflowCommand.ResumeWithFailedTask(
                nodeStack = event.nodeStack,
                error = InternalException.Error.from(e, forkNode.position)
            )
        }
    }


}
