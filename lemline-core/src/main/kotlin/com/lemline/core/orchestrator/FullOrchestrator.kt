// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.info
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.definitions.getNode
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
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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

            is WorkflowEvent.ListenForEachCompleted -> resume(
                workflow = workflow,
                command = handle(serdeEvent),
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook,
            )

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
    @Suppress("UNCHECKED_CAST")
    private suspend fun handle(
        workflow: Workflow,
        event: WorkflowEvent.ListenStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        logger.debug { "Listening for CloudEvents: strategy=${event.config.strategy} filters=${event.config.filters} until=${event.config.until}" }

        // Check if foreach is configured by looking at the node's children
        val listenNode = workflow.getNode(event.nodePosition) as Node<ListenTask>
        val hasForeach = listenNode.children != null

        // Use configured timeout or default to 30 seconds for testing
        val timeoutMillis = event.config.timeoutAt?.let {
            (it - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
        } ?: 3_000L

        return try {
            val events = withTimeout(timeoutMillis) {
                when (event.config.strategy) {
                    ListenStrategy.ONE -> {
                        // Wait for first matching event
                        val cloudEvent = cloudEventHook.receive()
                            .filter { matchesFilters(it, event.config.filters) }
                            .first()
                        listOf(cloudEvent.toJsonElement())
                    }

                    ListenStrategy.ANY -> {
                        // Check for until condition
                        when (val until = event.config.until) {
                            null -> {
                                // No until: wait for first matching event
                                val cloudEvent = cloudEventHook.receive()
                                    .filter { matchesFilters(it, event.config.filters) }
                                    .first()
                                listOf(cloudEvent.toJsonElement())
                            }

                            is UntilCondition.Expression -> {
                                // Accumulate events until expression evaluates to true
                                collectEventsUntilExpression(
                                    cloudEventHook = cloudEventHook,
                                    filters = event.config.filters,
                                    expression = until.expression
                                )
                            }

                            is UntilCondition.Event -> {
                                // Accumulate events until termination event arrives
                                collectEventsUntilTermination(
                                    cloudEventHook = cloudEventHook,
                                    filters = event.config.filters,
                                    terminationFilter = until.filter
                                )
                            }
                        }
                    }

                    ListenStrategy.ALL -> {
                        // Wait for one event per filter
                        event.config.filters.map { filter ->
                            cloudEventHook.receive()
                                .filter { matchesFilters(it, listOf(filter)) }
                                .first()
                                .toJsonElement()
                        }
                    }
                }
            }

            // If foreach is configured, process each event through foreach.do sequentially
            if (hasForeach && events.isNotEmpty()) {
                val foreachOutputs = processForeachEvents(
                    workflow = workflow,
                    event = event,
                    events = events,
                    serde = serde,
                    activityExecutor = activityExecutor,
                    cloudEventHook = cloudEventHook,
                    lifecycleHook = lifecycleHook
                )
                event.resumeCompleted(JsonArray(foreachOutputs))
            } else {
                event.resumeCompleted(JsonArray(events))
            }
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
     * Processes events through foreach.do tasks sequentially.
     *
     * For each event in the buffer:
     * 1. Create a ResumeFromTask command to execute foreach.do with the event as input
     * 2. Execute the foreach.do tasks by calling resume recursively
     * 3. Collect the iteration output
     * 4. Wait for the iteration to complete before processing the next event
     *
     * @param workflow The workflow definition
     * @param event The original ListenStarted event (for nodeStack context)
     * @param events List of collected CloudEvents (as JsonElements)
     * @param serde Whether to enable serde checking
     * @param activityExecutor Executor for activities
     * @param cloudEventHook Hook for CloudEvent operations
     * @param lifecycleHook Hook for lifecycle events
     * @return List of foreach iteration outputs
     */
    private suspend fun processForeachEvents(
        workflow: Workflow,
        event: WorkflowEvent.ListenStarted,
        events: List<JsonElement>,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook
    ): List<JsonElement> {
        val outputs = mutableListOf<JsonElement>()

        events.forEachIndexed { index, eventData ->
            logger.debug { "Processing foreach iteration $index with event: $eventData" }

            // Create a command to execute foreach.do with this event as input
            val foreachCommand = event.resumeForeach(eventData, index)

            // Execute the foreach.do tasks by recursively calling resume
            val outcome = resume(
                workflow = workflow,
                command = foreachCommand,
                serde = serde,
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = lifecycleHook
            )

            // Extract the output from the outcome
            // The workflow returns an array (from ListenForEachCompleted handler),
            // extract the first element which is the actual iteration output
            val workflowOutput = outcome.value()
            val iterationOutput = if (workflowOutput is JsonArray && workflowOutput.isNotEmpty()) {
                workflowOutput[0]
            } else {
                workflowOutput
            }
            outputs.add(iterationOutput)

            logger.debug { "Foreach iteration $index completed with output: $iterationOutput" }
        }

        return outputs
    }

    /**
     * Collects events until the given JQ expression evaluates to true.
     *
     * The expression is evaluated against the accumulated events array after each event.
     * Example expression: `. | any(.temperature > 38)` or `. | length >= 5`
     *
     * Uses flow's `first` operator with a predicate that:
     * 1. Accumulates matching events into a buffer
     * 2. Returns true when the expression evaluates to true (stopping collection)
     *
     * @param cloudEventHook Source of CloudEvents
     * @param filters Filters to match incoming events
     * @param expression JQ expression evaluated against accumulated events array
     * @return List of accumulated events (as JsonElements)
     */
    private suspend fun collectEventsUntilExpression(
        cloudEventHook: CloudEventHook,
        filters: List<EventFilter>,
        expression: String
    ): List<JsonElement> {
        val accumulated = mutableListOf<JsonElement>()

        // Use first with predicate - returns true when we should stop
        cloudEventHook.receive()
            .filter { matchesFilters(it, filters) }
            .first { cloudEvent ->
                val eventJson = cloudEvent.toJsonElement()
                accumulated.add(eventJson)

                logger.debug { "Accumulated event (count=${accumulated.size}): type=${cloudEvent.type}" }

                // Evaluate expression against accumulated events
                val shouldStop = evaluateExpressionAsBoolean($$"${$$expression}", JsonArray(accumulated))

                if (shouldStop) {
                    logger.debug { "Until expression evaluated to true after ${accumulated.size} events" }
                }

                shouldStop // Return true to stop collecting
            }

        return accumulated
    }

    /**
     * Collects events until a termination event arrives.
     *
     * Events matching the main filters are accumulated. When an event matching
     * the termination filter arrives, collection stops and accumulated events
     * are returned (the termination event is NOT included in the output).
     *
     * Uses flow's `first` operator with a predicate that:
     * 1. Checks if the event is a termination event (returns true to stop)
     * 2. Otherwise accumulates events matching main filters (returns false to continue)
     *
     * @param cloudEventHook Source of CloudEvents
     * @param filters Main filters to match and accumulate events
     * @param terminationFilter Filter for the termination event
     * @return List of accumulated events (excluding termination event)
     */
    private suspend fun collectEventsUntilTermination(
        cloudEventHook: CloudEventHook,
        filters: List<EventFilter>,
        terminationFilter: EventFilter
    ): List<JsonElement> {
        val accumulated = mutableListOf<JsonElement>()

        // Use first with predicate - returns true when termination event arrives
        cloudEventHook.receive()
            .first { cloudEvent ->
                // Check if this is the termination event
                if (matchesFilters(cloudEvent, listOf(terminationFilter))) {
                    logger.debug { "Termination event received: type=${cloudEvent.type}, returning ${accumulated.size} accumulated events" }
                    true // Stop collection
                } else {
                    // Check if this event matches our main filters
                    if (matchesFilters(cloudEvent, filters)) {
                        val eventJson = cloudEvent.toJsonElement()
                        accumulated.add(eventJson)
                        logger.debug { "Accumulated event (count=${accumulated.size}): type=${cloudEvent.type}" }
                    }
                    false // Continue collecting
                }
            }

        return accumulated
    }

    /**
     * Handles a ListenForEachCompleted event by resuming with the iteration output.
     *
     * In mock mode, this assumes a single-event scenario where one foreach iteration
     * completes and the listen task should resume with that output wrapped in an array.
     */
    private fun handle(event: WorkflowEvent.ListenForEachCompleted): WorkflowCommand {
        logger.debug { "Mock: ListenForEachCompleted iteration=${event.iterationIndex}" }
        // Resume with the single iteration output wrapped in an array
        return event.resumeCompleted(JsonArray(listOf(event.iterationOutput)))
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
        val childWorkflow = DefinitionCache.getWorkflow(
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
                    is WorkflowEvent.ForkBranchCompleted -> event.resumeAsCompleted(result.output)
                    is WorkflowEvent.WorkflowFailed -> event.resumeAsFailed(result.error)
                    is WorkflowEvent.ForkBranchFailed -> event.resumeAsFailed(result.error)
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

        val branches = forkNode.children
            ?: throw IllegalStateException("Fork node in ${forkNode.position} has no branches")

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
            if (!matchesExprField(filter.time, event.time?.toString())) return@any false

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
     * Convert a CloudEvent to JsonElement for workflow consumption.
     */
    private fun CloudEvent.toJsonElement(): JsonElement {
        return buildJsonObject {
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
}
