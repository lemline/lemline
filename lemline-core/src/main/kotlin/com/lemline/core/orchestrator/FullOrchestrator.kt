// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.info
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.expressions.JQExpression
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.Node
import com.lemline.core.processors.EmitConfig
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.processors.UntilCondition
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.utils.mapAwaitAllFailFast
import com.lemline.core.utils.mapAwaitFirstFailSlow
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.branches
import com.lemline.core.workflows.foreachBlock
import com.lemline.core.workflows.getNode
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI
import java.time.OffsetDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject


/**
 * Full orchestrator for synchronous workflow execution.
 *
 * This orchestrator executes workflows in a single pass, handling all events
 * (activities, waits, forks, child workflows) directly without external coordination.
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
        serde: Boolean = false,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): JsonElement {
        val cmd = StepByStepOrchestrator.initCmd(workflowId, workflowInput, hasWaitingParent, startedAt)

        return resume(workflow, cmd, serde, activityExecutor, cloudEventHook, lifecycleHook).value()
    }

    suspend fun resume(
        workflow: Workflow,
        command: WorkflowCommand,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent.Outcome {

        val serdeCommand = when (serde) {
            true -> WorkflowState.fromJsonString(command.toJsonString()) as WorkflowCommand
            false -> command
        }

        if (command != serdeCommand)
            throw IllegalStateException("Command mismatch\ncommand     : $command\nserdeCommand: $serdeCommand")

        val event = StepByStepOrchestrator.runByTask(workflow, serdeCommand, workflow.info, lifecycleHook)

        val serdeEvent = when (serde) {
            true -> WorkflowState.fromJsonString(event.toJsonString()) as WorkflowEvent
            false -> event
        }

        if (event != serdeEvent)
            throw IllegalStateException("Event mismatch\nevent     : $event\nserdeEvent: $serdeEvent")

        return when (serdeEvent) {
            is WorkflowEvent.ActivityStarted -> resume(
                workflow = workflow,
                command = handle(serdeEvent, activityExecutor),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.EmitStarted -> resume(
                workflow = workflow,
                command = handle(serdeEvent, cloudEventHook),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.WaitStarted -> resume(
                workflow = workflow,
                command = handle(serdeEvent),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.TaskScheduled -> resume(
                workflow = workflow,
                command = handle(serdeEvent),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.TaskRetryScheduled -> resume(
                workflow = workflow,
                command = handle(serdeEvent),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.RunWorkflowStarted -> resume(
                workflow = workflow,
                command = handle(serdeEvent, serde, activityExecutor, cloudEventHook, lifecycleHook),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.ForkStarted -> resume(
                workflow = workflow,
                command = handle(workflow, serdeEvent, serde, activityExecutor, cloudEventHook, lifecycleHook),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.ListenStarted -> resume(
                workflow = workflow,
                command = handle(workflow, serdeEvent, serde, activityExecutor, cloudEventHook, lifecycleHook),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

            // ListenForEachCompleted is now an Outcome, so it's handled here
            is WorkflowEvent.Outcome -> serdeEvent
        }
    }

    /**
     * Handles an ActivityStarted event by executing the activity and resuming with the result.
     *
     * Activities are executed via the ActivityExecutor interface, which allows for
     * different implementations (real I/O vs mocks for testing).
     */
    private suspend fun handle(
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

    /**
     * Handles a retry event by delaying execution until the scheduled retry time
     * and then resuming the workflow from the specified task.
     */
    private suspend fun handle(
        event: WorkflowEvent.TaskRetryScheduled
    ): WorkflowCommand {
        val delayDuration = event.retryAt - Clock.System.now()
        logger.debug { "Retrying in $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Retrying" }
        return event.resume()
    }

    /**
     * Handles an EmitStarted event by building a CloudEvent and emitting via CloudEventHook.
     */
    private suspend fun handle(
        event: WorkflowEvent.EmitStarted,
        cloudEventHook: CloudEventHook
    ): WorkflowCommand {
        logger.debug { "Emitting CloudEvent: type=${event.config.type} source=${event.config.source}" }
        val cloudEvent = buildCloudEvent(event.config)
        cloudEventHook.emit(cloudEvent)
        return event.resume()
    }

    /**
     * Handles a ListenStarted event by receiving CloudEvents from CloudEventHook.
     *
     * The hook provides a raw event stream; this handler applies the listen configuration
     * (strategy, filters) to determine when to resume the workflow.
     *
     * Uses a timeout to prevent infinite waits when no matching events are received.
     * Default timeout is 30 seconds for in-memory testing scenarios.
     *
     * ## Strategy Behavior
     *
     * - **ONE**: Wait for first matching event, return single-element array
     * - **ANY (no until)**: Wait for first matching event, return single-element array
     * - **ANY + until(expression)**: Accumulate events, evaluate expression after each,
     *   stop when expression returns true
     * - **ANY + until(event)**: Accumulate events until termination event arrives
     * - **ALL**: Wait for one event per filter
     *
     * ## Foreach Processing
     *
     * If the listen task has `foreach` configured, each event is processed sequentially
     * through the foreach.do tasks. The output is an array of foreach iteration outputs,
     * not the raw events.
     */
    /**
     * Context for foreach processing, containing all dependencies needed
     * to execute foreach.do tasks as events arrive.
     *
     * @property currentNodeStack Mutable nodeStack that gets updated after each iteration
     *           to propagate context changes (via export.as) across iterations.
     */
    private class ForeachContext(
        val workflow: Workflow,
        val listenEvent: WorkflowEvent.ListenStarted,
        val serde: Boolean,
        val activityExecutor: ActivityExecutor,
        val cloudEventHook: CloudEventHook,
        val lifecycleHook: LifecycleEventHook,
    ) {
        var currentNodeStack: NodeStack = listenEvent.nodeStack
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun handle(
        workflow: Workflow,
        event: WorkflowEvent.ListenStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        val listenNode = workflow.getNode(event.nodePosition) as Node<ListenTask>
        logger.debug { "Listening for CloudEvents: strategy=${event.config.strategy} filters=${event.config.filters} until=${event.config.until}" }

        // Check if foreach is configured by looking at the node's foreachBlock
        val hasForeach = listenNode.foreachBlock != null

        // Create foreach context if foreach is configured
        val foreachCtx = if (hasForeach) {
            ForeachContext(workflow, event, serde, activityExecutor, cloudEventHook, lifecycleHook)
        } else {
            null
        }

        // Use configured timeout
        val timeoutMillis = event.config.timeoutAt?.let {
            (it - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
        } ?: Long.MAX_VALUE

        // Wrap in try/catch like fork does - errors from foreach are caught and converted to ResumeWithFailedTask
        return try {
            withTimeout(timeoutMillis) {
                when (event.config.strategy) {
                    ListenStrategy.ONE -> {
                        // Wait for first matching event
                        val cloudEvent = cloudEventHook.receive()
                            .filter { matchesFilters(it, event.config.filters) }
                            .first()
                        val eventJson = cloudEvent.toJsonElement(event.config.readAs)
                        // Process through foreach immediately if configured
                        val output = if (foreachCtx != null) {
                            processEventThroughForeach(foreachCtx, eventJson, 0)
                        } else {
                            eventJson
                        }
                        event.resumeCompleted(JsonArray(listOf(output)))
                    }

                    ListenStrategy.ANY -> {
                        // Check for until condition
                        when (val until = event.config.until) {
                            null -> {
                                // No until: wait for first matching event
                                val cloudEvent = cloudEventHook.receive()
                                    .filter { matchesFilters(it, event.config.filters) }
                                    .first()
                                val eventJson = cloudEvent.toJsonElement(event.config.readAs)
                                // Process through foreach immediately if configured
                                val output = if (foreachCtx != null) {
                                    processEventThroughForeach(foreachCtx, eventJson, 0)
                                } else {
                                    eventJson
                                }
                                event.resumeCompleted(JsonArray(listOf(output)))
                            }

                            is UntilCondition.Expression -> {
                                // Accumulate events until expression evaluates to true
                                // Process each event through foreach as it arrives
                                val outputs = collectEventsUntilExpression(
                                    cloudEventHook = cloudEventHook,
                                    filters = event.config.filters,
                                    expression = until.expression,
                                    readAs = event.config.readAs,
                                    foreachCtx = foreachCtx
                                )
                                event.resumeCompleted(JsonArray(outputs))
                            }

                            is UntilCondition.Event -> {
                                // Accumulate events until termination event arrives
                                // Process each event through foreach as it arrives
                                val outputs = collectEventsUntilTermination(
                                    cloudEventHook = cloudEventHook,
                                    filters = event.config.filters,
                                    terminationFilter = until.filter,
                                    readAs = event.config.readAs,
                                    foreachCtx = foreachCtx
                                )
                                event.resumeCompleted(JsonArray(outputs))
                            }
                        }
                    }

                    ListenStrategy.ALL -> {
                        // Wait for one event per filter, process each through foreach as it arrives
                        val outputs = mutableListOf<JsonElement>()
                        for ((index, filter) in event.config.filters.withIndex()) {
                            val cloudEvent = cloudEventHook.receive()
                                .filter { matchesFilters(it, listOf(filter)) }
                                .first()
                            val eventJson = cloudEvent.toJsonElement(event.config.readAs)
                            val output = if (foreachCtx != null) {
                                processEventThroughForeach(foreachCtx, eventJson, index)
                            } else {
                                eventJson
                            }
                            outputs.add(output)
                        }
                        event.resumeCompleted(JsonArray(outputs))
                    }
                }
            }
        } catch (e: InternalException) {
            // Error from foreach.do - convert to ResumeWithFailedTask like fork does
            // This lets the error flow through normal workflow error handling (try/catch, etc.)
            // Use the updated nodeStack from foreachCtx if available (preserves context from previous iterations)
            logger.error { "Listen foreach failed: error=$e" }
            WorkflowCommand.ResumeWithFailedTask(
                nodeStack = foreachCtx?.currentNodeStack ?: event.nodeStack,
                error = InternalException.Error.from(e, listenNode.position)
            )
        } catch (_: TimeoutCancellationException) {
            logger.error { "Listen timed out waiting for matching events: filters=${event.config.filters}" }
            event.resumeFailed(
                InternalException.Error.from(
                    IllegalStateException("Listen timeout: no matching CloudEvent received within ${timeoutMillis}ms"),
                    event.nodePosition
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Listen failed" }
            event.resumeFailed(InternalException.Error.from(e, event.nodePosition))
        }
    }

    /**
     * Processes a single event through foreach.do tasks.
     *
     * This is called as each event arrives, not after all events are collected.
     * The foreach.do tasks are executed synchronously before the next event is processed.
     *
     * IMPORTANT: Uses ctx.currentNodeStack (not the original listenEvent.nodeStack) to ensure
     * context changes from previous iterations (via export.as) are available in subsequent iterations.
     * After processing, updates ctx.currentNodeStack with the outcome's nodeStack.
     *
     * @param ctx Foreach context containing workflow and execution dependencies
     * @param eventData The CloudEvent data to process
     * @param iterationIndex The iteration index (0-based)
     * @return The output from the foreach iteration
     * @throws InternalException if the foreach iteration fails
     */
    private suspend fun processEventThroughForeach(
        ctx: ForeachContext,
        eventData: JsonElement,
        iterationIndex: Int
    ): JsonElement {
        logger.debug { "Processing foreach iteration $iterationIndex with event: $eventData" }

        @Suppress("UNCHECKED_CAST")
        val listenNode = ctx.workflow.getNode(ctx.listenEvent.nodePosition) as Node<ListenTask>
        val foreachPosition = listenNode.foreachBlock?.position
            ?: error("Listen task at ${ctx.listenEvent.nodePosition} has no foreach block")

        val foreachCommand = WorkflowCommand.ResumeFromTask(
            nodeStack = ctx.currentNodeStack,
            nodePosition = foreachPosition,
            rawInput = eventData,
        )

        // Execute the foreach.do tasks - resume() returns when it hits ListenForEachCompleted (an Outcome)
        val outcome = resume(
            workflow = ctx.workflow,
            command = foreachCommand,
            serde = ctx.serde,
            activityExecutor = ctx.activityExecutor,
            cloudEventHook = ctx.cloudEventHook,
            lifecycleHook = ctx.lifecycleHook
        )

        return when (outcome) {
            is WorkflowEvent.ListenForEachCompleted -> {
                // Update the nodeStack for the next iteration to preserve context changes
                ctx.currentNodeStack = outcome.nodeStack
                val iterationOutput = outcome.output
                logger.debug { "Foreach iteration $iterationIndex completed with output: $iterationOutput" }
                iterationOutput
            }

            is WorkflowEvent.WorkflowFailed -> {
                // Error occurred in foreach.do - throw so the listen handler can catch it
                // and create ResumeWithFailedTask (like fork does)
                logger.debug { "Foreach iteration $iterationIndex failed with error: ${outcome.error}" }
                throw InternalException(outcome.error)
            }

            else -> {
                // Unexpected outcome - this shouldn't happen in normal execution
                throw IllegalStateException("Unexpected outcome from foreach iteration: $outcome")
            }
        }
    }

    /**
     * Collects events until the given JQ expression evaluates to true.
     *
     * The expression is evaluated against the accumulated RAW events array after each event.
     * Example expression: `. | any(.data.value > 100)` or `. | length >= 5`
     *
     * If foreach is configured, each event is processed through foreach.do as it arrives.
     * Events are processed SEQUENTIALLY - we wait for foreach.do to complete before
     * receiving the next event. The expression is always evaluated against raw events
     * for consistency, but the final output contains foreach outputs (not raw events).
     *
     * @param cloudEventHook Source of CloudEvents
     * @param filters Filters to match incoming events
     * @param expression JQ expression evaluated against accumulated raw events array
     * @param foreachCtx If non-null, process events through foreach.do as they arrive
     * @return List of collected outputs (foreach outputs if configured, otherwise raw events)
     * @throws InternalException if a foreach iteration fails
     */
    private suspend fun collectEventsUntilExpression(
        cloudEventHook: CloudEventHook,
        filters: List<EventFilter>,
        expression: String,
        readAs: ListenAndReadAs,
        foreachCtx: ForeachContext?
    ): List<JsonElement> {
        // Track raw events for until expression evaluation
        val rawEvents = mutableListOf<JsonElement>()
        // Track outputs (foreach outputs if configured, otherwise same as raw events)
        val outputs = mutableListOf<JsonElement>()

        // Use a channel to ensure truly sequential processing:
        // We receive one event, fully process it (including foreach.do), then receive the next
        val scope = CoroutineScope(currentCoroutineContext())
        val channel: ReceiveChannel<CloudEvent> = cloudEventHook.receive()
            .filter { matchesFilters(it, filters) }
            .produceIn(scope)

        try {
            for (cloudEvent in channel) {
                val eventJson = cloudEvent.toJsonElement(readAs)
                rawEvents.add(eventJson)

                // Process through foreach - MUST complete before we receive next event
                // If foreach fails, it throws InternalException which propagates up
                val outputJson = if (foreachCtx != null) {
                    processEventThroughForeach(foreachCtx, eventJson, outputs.size)
                } else {
                    eventJson
                }
                outputs.add(outputJson)

                logger.debug { "Accumulated event (count=${rawEvents.size}): type=${cloudEvent.type}" }

                // Evaluate expression against raw events (not foreach outputs)
                val shouldStop = evaluateExpressionAsBoolean(expression, JsonArray(rawEvents))

                if (shouldStop) {
                    logger.debug { "Until expression evaluated to true after ${rawEvents.size} events" }
                    break
                }
            }
        } finally {
            channel.cancel()
        }

        return outputs
    }

    /**
     * Collects events until a termination event arrives.
     *
     * Events matching the main filters are accumulated. When an event matching
     * the termination filter arrives, collection stops and accumulated events
     * are returned (the termination event is NOT included in the output).
     *
     * If foreach is configured, each event is processed through foreach.do as it arrives.
     * Events are processed SEQUENTIALLY - we wait for foreach.do to complete before
     * receiving the next event.
     *
     * @param cloudEventHook Source of CloudEvents
     * @param filters Main filters to match and accumulate events
     * @param terminationFilter Filter for the termination event
     * @param foreachCtx If non-null, process events through foreach.do as they arrive
     * @return List of collected outputs (foreach outputs if configured, otherwise raw events)
     * @throws InternalException if a foreach iteration fails
     */
    private suspend fun collectEventsUntilTermination(
        cloudEventHook: CloudEventHook,
        filters: List<EventFilter>,
        terminationFilter: EventFilter,
        readAs: ListenAndReadAs,
        foreachCtx: ForeachContext?
    ): List<JsonElement> {
        val outputs = mutableListOf<JsonElement>()

        // Use a channel to ensure truly sequential processing
        val scope = CoroutineScope(currentCoroutineContext())
        val channel: ReceiveChannel<CloudEvent> = cloudEventHook.receive()
            .produceIn(scope)

        try {
            for (cloudEvent in channel) {
                // Check if this is the termination event
                if (matchesFilters(cloudEvent, listOf(terminationFilter))) {
                    logger.debug { "Termination event received: type=${cloudEvent.type}, returning ${outputs.size} accumulated events" }
                    break
                }

                // Check if this event matches our main filters
                if (matchesFilters(cloudEvent, filters)) {
                    val eventJson = cloudEvent.toJsonElement(readAs)

                    // Process through foreach - MUST complete before we receive next event
                    // If foreach fails, it throws InternalException which propagates up
                    val outputJson = if (foreachCtx != null) {
                        processEventThroughForeach(foreachCtx, eventJson, outputs.size)
                    } else {
                        eventJson
                    }
                    outputs.add(outputJson)

                    logger.debug { "Accumulated event (count=${outputs.size}): type=${cloudEvent.type}" }
                }
            }
        } finally {
            channel.cancel()
        }

        return outputs
    }

    /**
     * Handles a `RunWorkflowStarted` event by initiating the corresponding child workflow either
     * synchronously or asynchronously, depending on its configuration.
     */
    private suspend fun handle(
        event: WorkflowEvent.RunWorkflowStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        // Retrieve child workflow definition
        val childWorkflow = WorkflowCache.getWorkflow(
            namespace = event.config.namespace,
            name = event.config.name,
            version = event.config.version
        ) ?: throw IllegalStateException("Workflow definition not found")

        return when (event.config.sync) {
            true -> {
                // synchronous execution
                val initCmd = StepByStepOrchestrator.initCmd(
                    workflowInput = event.config.input,
                    hasWaitingParent = true
                )
                val result = resume(childWorkflow, initCmd, serde, activityExecutor, cloudEventHook, lifecycleHook)
                logger.debug { "Child workflow completed" }
                when (result) {
                    is WorkflowEvent.WorkflowCompleted -> event.resumeAsCompleted(result.output)
                    is WorkflowEvent.WorkflowFailed -> event.resumeAsFailed(result.error)
                    // ForkBranchCompleted, ForkBranchFailed, and ListenForEachCompleted are internal
                    // events that should never escape from a child workflow
                    else -> throw IllegalStateException(
                        "Child workflow returned unexpected outcome: $result"
                    )
                }
            }

            false -> {
                // asynchronous execution
                CoroutineScope(currentCoroutineContext()).launch {
                    val initCmd = StepByStepOrchestrator.initCmd(
                        workflowInput = event.config.input,
                        hasWaitingParent = false
                    )
                    resume(childWorkflow, initCmd, serde, activityExecutor, cloudEventHook, lifecycleHook)
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
        val delayDuration = event.config.waitUntil - Clock.System.now()
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
    private suspend fun handle(
        workflow: Workflow,
        event: WorkflowEvent.ForkStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        @Suppress("UNCHECKED_CAST")
        val forkNode = workflow.getNode(event.nodePosition) as Node<ForkTask>

        val branches = forkNode.branches.ifEmpty {
            throw IllegalStateException("Fork node in ${forkNode.position} has no branches")
        }

        // Execute branches and get the result
        return try {
            val output = if (forkNode.task.fork.isCompete) {
                workflow.executeCompete(
                    event.nodeStack,
                    branches,
                    event.rawInput,
                    serde,
                    activityExecutor,
                    cloudEventHook,
                    lifecycleHook
                )
            } else {
                workflow.executeCooperative(
                    event.nodeStack,
                    branches,
                    event.rawInput,
                    serde,
                    activityExecutor,
                    cloudEventHook,
                    lifecycleHook
                )
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
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): JsonElement {
        // Get the first success - if all branches failed, the last exception will be rethrown from here
        return branches.mapAwaitFirstFailSlow { branchNode ->
            lifecycleHook.onTaskCreated(
                workflowInfo = info,
                nodeStack = nodeStack,
                nodePosition = branchNode.position,
                input = rawInput,
                createdAt = Clock.System.now(),
            )

            resume(
                workflow = this,
                command = WorkflowCommand.ResumeFromTask(
                    nodeStack = nodeStack,
                    nodePosition = branchNode.position,
                    rawInput = rawInput,
                    flowDirective = null
                ),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
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
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): JsonArray {
        // Get all results - If a branch failed, the first exception will be rethrown from here
        return JsonArray(
            branches.mapAwaitAllFailFast { branchNode ->
                lifecycleHook.onTaskCreated(
                    workflowInfo = info,
                    nodeStack = nodeStack,
                    nodePosition = branchNode.position,
                    input = rawInput,
                    createdAt = Clock.System.now(),
                )

                resume(
                    workflow = this,
                    command = WorkflowCommand.ResumeFromTask(
                        nodeStack = nodeStack,
                        nodePosition = branchNode.position,
                        rawInput = rawInput,
                        flowDirective = null
                    ),
                    serde = serde,
                    activityExecutor = activityExecutor,
                    cloudEventHook = cloudEventHook,
                    lifecycleHook = lifecycleHook,
                ).value()
            })
    }

    // ========================================
    // CloudEvent Helpers
    // ========================================

    /**
     * Build a CloudEvent from EmitConfig.
     */
    private fun buildCloudEvent(config: EmitConfig): CloudEvent {
        val builder = CloudEventBuilder.v1()
            .withId(config.id)
            .withSource(URI.create(config.source))
            .withType(config.type)

        config.time?.let { builder.withTime(OffsetDateTime.parse(it)) }
        config.subject?.let { builder.withSubject(it) }
        config.dataschema?.let { builder.withDataSchema(URI.create(it)) }
        config.datacontenttype?.let { builder.withDataContentType(it) }
        config.data?.let { builder.withData(it.toString().toByteArray()) }
        config.extensions?.forEach { (key, value) ->
            builder.withExtension(key, value)
        }

        return builder.build()
    }

    /**
     * Check if a CloudEvent matches any of the given filters.
     *
     * Supports all CloudEvent filter properties:
     * - Literal-only fields (exact match): type, id, subject, datacontenttype
     * - Expression-capable fields: source, dataschema, time, data (dataFilter)
     */
    private fun matchesFilters(event: CloudEvent, filters: List<EventFilter>): Boolean {
        if (filters.isEmpty()) return true // Empty filters = wildcard

        // Parse event data once (lazily) for data filter evaluation
        val eventData by lazy { parseEventData(event) }

        return filters.any { filter ->
            // Literal-only fields: exact string match
            if (!matchesLiteralField(filter.type, event.type)) return@any false
            if (!matchesLiteralField(filter.id, event.id)) return@any false
            if (!matchesLiteralField(filter.subject, event.subject)) return@any false
            if (!matchesLiteralField(filter.datacontenttype, event.dataContentType)) return@any false

            // Expression-capable fields
            if (!matchesExprField(filter.source, event.source?.toString())) return@any false
            if (!matchesExprField(filter.dataschema, event.dataSchema?.toString())) return@any false
            if (!matchesTimeField(filter.time, event.time)) return@any false

            // Data filter (expression against event payload)
            if (!matchesDataFilter(filter.dataFilter, eventData)) return@any false

            true
        }
    }

    /**
     * Matches a literal-only field (exact string match).
     */
    private fun matchesLiteralField(filterValue: String?, eventValue: String?): Boolean {
        if (filterValue == null) return true
        return filterValue == eventValue
    }

    /**
     * Matches an expression-capable field.
     * If the filter value is an expression (starts with ${), evaluate it against the event value.
     * Otherwise, do exact string match.
     */
    private fun matchesExprField(filterValue: String?, eventValue: String?): Boolean {
        if (filterValue == null) return true

        return if (ExpressionUtils.isExpr(filterValue)) {
            evaluateExpressionAsBoolean(filterValue, eventValue?.let { JsonPrimitive(it) } ?: JsonNull)
        } else {
            filterValue == eventValue
        }
    }

    private fun matchesTimeField(filterValue: String?, eventTime: OffsetDateTime?): Boolean {
        if (filterValue == null) return true
        if (eventTime == null) return false

        return if (ExpressionUtils.isExpr(filterValue)) {
            evaluateExpressionAsBoolean(filterValue, JsonPrimitive(eventTime.toString()))
        } else {
            compareTimestampsNormalized(filterValue, eventTime)
        }
    }

    private fun compareTimestampsNormalized(filterValue: String, eventTime: OffsetDateTime): Boolean {
        return try {
            val filterTime = OffsetDateTime.parse(filterValue)
            filterTime.isEqual(eventTime)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse filter time value as OffsetDateTime: $filterValue" }
            filterValue == eventTime.toString()
        }
    }

    /**
     * Matches data filter expression against event payload.
     * The filter expression is evaluated against the event data and must return boolean.
     */
    private fun matchesDataFilter(dataFilter: String?, eventData: JsonElement): Boolean {
        if (dataFilter == null) return true
        if (eventData == JsonNull) return false

        return evaluateExpressionAsBoolean("\${$dataFilter}", eventData)
    }

    /**
     * Evaluates a JQ expression against input and expects a boolean result.
     */
    private fun evaluateExpressionAsBoolean(expression: String, input: JsonElement): Boolean {
        return try {
            val trimmedExpr = ExpressionUtils.trimExpr(expression)
            val result = with(LemlineJson) {
                val inputNode = input.toJsonNode()
                val scope = JsonObject(emptyMap()).toJsonNode() as ObjectNode
                JQExpression.eval(inputNode, trimmedExpr, scope).toJsonElement()
            }
            (result as? JsonPrimitive)?.booleanOrNull == true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to evaluate expression: $expression" }
            false
        }
    }

    /**
     * Parses the CloudEvent data payload to JsonElement.
     */
    private fun parseEventData(event: CloudEvent): JsonElement {
        val data = event.data ?: return JsonNull
        return try {
            val bytes = data.toBytes()
            if (bytes.isEmpty()) {
                JsonNull
            } else {
                Json.parseToJsonElement(String(bytes))
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse CloudEvent data as JSON" }
            JsonNull
        }
    }

    /**
     * Convert a CloudEvent to JsonElement based on the readAs mode.
     *
     * @param readAs How to extract event content (DATA, ENVELOPE, RAW)
     * @return The extracted content as JsonElement
     */
    private fun CloudEvent.toJsonElement(readAs: ListenAndReadAs): JsonElement {
        return when (readAs) {
            ListenAndReadAs.DATA -> {
                // Extract just the data payload
                data?.let {
                    val dataString = String(it.toBytes())
                    try {
                        Json.parseToJsonElement(dataString)
                    } catch (_: Exception) {
                        JsonPrimitive(dataString)
                    }
                } ?: JsonNull
            }

            ListenAndReadAs.ENVELOPE -> {
                // Return the full CloudEvent structure
                buildJsonObject {
                    put("specversion", JsonPrimitive(specVersion.toString()))
                    put("id", JsonPrimitive(id))
                    put("source", JsonPrimitive(source.toString()))
                    put("type", JsonPrimitive(type))
                    time?.let { put("time", JsonPrimitive(it.toString())) }
                    subject?.let { put("subject", JsonPrimitive(it)) }
                    dataSchema?.let { put("dataschema", JsonPrimitive(it.toString())) }
                    dataContentType?.let { put("datacontenttype", JsonPrimitive(it)) }
                    data?.let {
                        val dataString = String(it.toBytes())
                        try {
                            put("data", Json.parseToJsonElement(dataString))
                        } catch (_: Exception) {
                            put("data", JsonPrimitive(dataString))
                        }
                    }
                }
            }

            ListenAndReadAs.RAW -> {
                // Return raw bytes as string
                data?.let { JsonPrimitive(String(it.toBytes())) } ?: JsonNull
            }
        }
    }
}
