// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.full

import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.core.cloudevents.CloudEventUtils
import com.lemline.core.cloudevents.CloudEventUtils.toJsonElement
import com.lemline.core.errors.InternalException
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.processors.UntilCondition
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal sealed class CollectResult {
    data class Success(val outputs: List<JsonElement>) : CollectResult()
    data class Failure(val nodeStack: NodeStack, val error: InternalException) : CollectResult()
}

@ExperimentalTime
internal typealias ForeachProcessor = suspend (NodeStack, JsonElement, Int) -> Pair<NodeStack, JsonElement>

@ExperimentalTime
internal object ListenEventCollector {

    private val logger = logger()

    suspend fun collect(
        event: WorkflowEvent.ListenStarted,
        eventFlow: Flow<CloudEvent>,
        foreachProcessor: ForeachProcessor?,
    ): CollectResult {
        var currentStack = event.nodeStack

        val processEvent: suspend (CloudEvent, Int) -> JsonElement = { cloudEvent, index ->
            val eventJson = cloudEvent.toJsonElement(event.config.readAs)
            if (foreachProcessor != null) {
                val (updatedStack, output) = foreachProcessor(currentStack, eventJson, index)
                currentStack = updatedStack
                output
            } else {
                eventJson
            }
        }

        return try {
            val outputs = when (event.config.strategy) {
                ListenStrategy.ONE -> collectFirst(eventFlow, event.config.filters, processEvent)
                ListenStrategy.ANY -> collectAny(eventFlow, event, processEvent)
                ListenStrategy.ALL -> collectAll(eventFlow, event.config.filters, processEvent)
            }
            CollectResult.Success(outputs)
        } catch (e: InternalException) {
            CollectResult.Failure(currentStack, e)
        }
    }

    private suspend fun collectFirst(
        eventFlow: Flow<CloudEvent>,
        filters: List<EventFilter>,
        processEvent: suspend (CloudEvent, Int) -> JsonElement,
    ): List<JsonElement> {
        val cloudEvent = eventFlow
            .filter { CloudEventUtils.matchesFilters(it, filters) }
            .first()
        return listOf(processEvent(cloudEvent, 0))
    }

    private suspend fun collectAny(
        eventFlow: Flow<CloudEvent>,
        event: WorkflowEvent.ListenStarted,
        processEvent: suspend (CloudEvent, Int) -> JsonElement,
    ): List<JsonElement> = when (val until = event.config.until) {
        null -> collectFirst(eventFlow, event.config.filters, processEvent)

        is UntilCondition.Expression -> collectUntilExpression(
            eventFlow, event.config.filters, until.expression, event.config.readAs, processEvent
        )

        is UntilCondition.Event -> collectUntilTermination(
            eventFlow, event.config.filters, until.filter, processEvent
        )
    }

    /**
     * Collect events for ALL strategy: waits until each filter has at least one match.
     * One event can satisfy multiple filters. Returns unique matched events sorted by
     * the index of their first matching filter.
     */
    private suspend fun collectAll(
        eventFlow: Flow<CloudEvent>,
        filters: List<EventFilter>,
        processEvent: suspend (CloudEvent, Int) -> JsonElement,
    ): List<JsonElement> {
        data class MatchedEvent(val firstFilterIndex: Int, val result: JsonElement)

        val matchedEvents = mutableListOf<MatchedEvent>()
        val unsatisfiedIndices = filters.indices.toMutableSet()

        val channel = eventFlow.toChannel()
        try {
            for (cloudEvent in channel) {
                val matchingIndices = unsatisfiedIndices.filter { index ->
                    CloudEventUtils.matchesFilters(cloudEvent, listOf(filters[index]))
                }

                if (matchingIndices.isNotEmpty()) {
                    val firstMatchingIndex = matchingIndices.min()
                    val processedResult = processEvent(cloudEvent, firstMatchingIndex)
                    matchedEvents.add(MatchedEvent(firstMatchingIndex, processedResult))

                    for (index in matchingIndices) {
                        unsatisfiedIndices.remove(index)
                        logger.debug { "Filter $index satisfied by event type=${cloudEvent.type}" }
                    }
                }

                if (unsatisfiedIndices.isEmpty()) {
                    logger.debug { "All ${filters.size} filters satisfied" }
                    break
                }
            }
        } finally {
            channel.cancel()
        }

        return matchedEvents.map { it.result }
    }

    private suspend fun collectUntilExpression(
        eventFlow: Flow<CloudEvent>,
        filters: List<EventFilter>,
        expression: String,
        readAs: ListenAndReadAs,
        processEvent: suspend (CloudEvent, Int) -> JsonElement,
    ): List<JsonElement> {
        val rawEvents = mutableListOf<JsonElement>()
        val outputs = mutableListOf<JsonElement>()

        val channel = eventFlow.filtered(filters)
        try {
            for (cloudEvent in channel) {
                rawEvents.add(cloudEvent.toJsonElement(readAs))
                outputs.add(processEvent(cloudEvent, outputs.size))

                logger.debug { "Accumulated event (count=${rawEvents.size}): type=${cloudEvent.type}" }

                if (CloudEventUtils.evaluateExpressionAsBoolean(expression, JsonArray(rawEvents))) {
                    logger.debug { "Until expression evaluated to true after ${rawEvents.size} events" }
                    break
                }
            }
        } finally {
            channel.cancel()
        }
        return outputs
    }

    private suspend fun collectUntilTermination(
        eventFlow: Flow<CloudEvent>,
        filters: List<EventFilter>,
        terminationFilter: EventFilter,
        processEvent: suspend (CloudEvent, Int) -> JsonElement,
    ): List<JsonElement> {
        val outputs = mutableListOf<JsonElement>()

        val channel = eventFlow.toChannel()
        try {
            for (cloudEvent in channel) {
                if (CloudEventUtils.matchesFilters(cloudEvent, listOf(terminationFilter))) {
                    logger.debug { "Termination event received: type=${cloudEvent.type}, returning ${outputs.size} accumulated events" }
                    break
                }
                if (CloudEventUtils.matchesFilters(cloudEvent, filters)) {
                    outputs.add(processEvent(cloudEvent, outputs.size))
                    logger.debug { "Accumulated event (count=${outputs.size}): type=${cloudEvent.type}" }
                }
            }
        } finally {
            channel.cancel()
        }
        return outputs
    }

    private suspend fun Flow<CloudEvent>.filtered(filters: List<EventFilter>): ReceiveChannel<CloudEvent> {
        val scope = CoroutineScope(currentCoroutineContext())
        return this.filter { CloudEventUtils.matchesFilters(it, filters) }.produceIn(scope)
    }

    private suspend fun Flow<CloudEvent>.toChannel(): ReceiveChannel<CloudEvent> {
        val scope = CoroutineScope(currentCoroutineContext())
        return this.produceIn(scope)
    }

    fun createForeachProcessor(
        foreachPosition: NodePosition,
        resumeFn: suspend (WorkflowCommand) -> WorkflowEvent.Outcome,
    ): ForeachProcessor = { nodeStack, eventData, iterationIndex ->
        logger.debug { "Processing foreach iteration $iterationIndex with event: $eventData" }

        val foreachCommand = WorkflowCommand.ResumeFromTask(
            nodeStack = nodeStack,
            nodePosition = foreachPosition,
            rawInput = eventData,
        )

        when (val outcome = resumeFn(foreachCommand)) {
            is WorkflowEvent.ForEachCompleted -> {
                logger.debug { "Foreach iteration $iterationIndex completed with output: ${outcome.output}" }
                outcome.nodeStack to outcome.output
            }

            is WorkflowEvent.WorkflowFailed -> {
                logger.debug { "Foreach iteration $iterationIndex failed with error: ${outcome.error}" }
                throw InternalException(outcome.error)
            }

            else -> throw IllegalStateException("Unexpected outcome from foreach iteration: $outcome")
        }
    }
}
