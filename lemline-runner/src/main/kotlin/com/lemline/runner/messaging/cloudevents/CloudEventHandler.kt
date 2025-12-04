// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.logger.logger
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.definitions.getNode
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.definitions.DefinitionListenService
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.ListenerRepository
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import io.serverlessworkflow.api.types.OneEventConsumptionStrategy
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles incoming CloudEvents by matching them against active listeners
 * and completing workflows that are waiting for matching events.
 *
 * ## Processing Flow
 *
 * 1. **Service Query**: Use DefinitionListenService to find matching listeners
 * 2. **Workflow Lookup**: Get listen task config from cached workflow definition
 * 3. **Strategy Handling**: Apply ONE/ANY/ALL strategy logic
 * 4. **Completion**: Resume matched workflows with the event data
 *
 * ## Strategy Behavior
 *
 * - **ONE**: First matching event completes the listener
 * - **ANY**: First matching event completes (or accumulates if `until` is set)
 * - **ALL**: All filters must match once; event indices are tracked
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
internal class CloudEventHandler(
    private val definitionListenService: DefinitionListenService,
    private val listenerRepository: ListenerRepository,
    private val commandEmitter: WorkflowCommandEmitter,
) {
    private val logger = logger()

    /**
     * Processes an incoming CloudEvent by matching it against active listeners.
     *
     * @param event The incoming CloudEvent
     * @return Number of listeners that were affected (completed or updated)
     */
    suspend fun handleCloudEvent(event: CloudEvent): Int {
        val eventType: String = event.type

        logger.debug { "Processing CloudEvent: type=$eventType, source=${event.source}, id=${event.id}" }

        // Find all matching listeners using the service
        val listeners = definitionListenService.findMatchingListeners(event)
        if (listeners.isEmpty()) {
            logger.trace { "No matching listeners for event type: $eventType" }
            return 0
        }

        logger.debug { "Found ${listeners.size} matching listeners for event type: $eventType" }

        var affectedCount = 0

        // Process each matched listener
        for (listener in listeners) {
            val affected = processListener(event, listener)
            if (affected) affectedCount++
        }

        logger.debug { "CloudEvent processing complete: $affectedCount listeners affected" }
        return affectedCount
    }

    /**
     * Processes a listener that matched the event.
     *
     * @param event The incoming CloudEvent
     * @param listener The listener that matched
     * @return true if the listener was affected (completed or updated)
     */
    private suspend fun processListener(
        event: CloudEvent,
        listener: ListenerModel
    ): Boolean {
        // Get listen task configuration from workflow definition
        val listenTaskConfig = getListenTaskConfig(listener)
        if (listenTaskConfig == null) {
            logger.warn { "Could not find listen task for listener ${listener.id}" }
            return false
        }

        val (strategy, readAs, filters) = listenTaskConfig

        logger.debug { "Processing listener ${listener.id}, strategy=$strategy" }

        // Apply strategy-specific logic
        return when (strategy) {
            ListenStrategy.ONE -> handleOneMatch(event, listener, readAs)
            ListenStrategy.ANY -> handleAnyMatch(event, listener, readAs)
            ListenStrategy.ALL -> {
                // For ALL strategy, find which filter index matched this event
                val filterIndex = findMatchingFilterIndex(event, filters)
                if (filterIndex == null) {
                    logger.warn { "Could not determine filter index for ALL strategy listener ${listener.id}" }
                    return false
                }
                handleAllMatch(event, listener, readAs, filters.size, filterIndex)
            }
        }
    }

    /**
     * Listen task configuration retrieved from cached workflow definition.
     */
    private data class ListenTaskConfig(
        val strategy: ListenStrategy,
        val readAs: ListenAndReadAs,
        val filters: List<io.serverlessworkflow.api.types.EventFilter>
    )

    /**
     * Gets the listen task configuration from the cached workflow definition.
     *
     * @return ListenTaskConfig or null if not found
     */
    private fun getListenTaskConfig(listener: ListenerModel): ListenTaskConfig? {
        val workflow = DefinitionCache.getWorkflow(
            namespace = listener.workflowNamespace,
            name = listener.workflowName,
            version = listener.workflowVersion
        ) ?: return null

        val node = try {
            workflow.getNode(listener.workflowPosition)
        } catch (e: Exception) {
            logger.warn(e) { "Node not found at ${listener.workflowPosition} in workflow" }
            return null
        }

        val listenTask = node.task as? ListenTask ?: return null

        // Determine strategy and filters from consumption strategy type
        val listenTo = listenTask.listen?.to?.get()
        val (strategy, filters) = when (listenTo) {
            is OneEventConsumptionStrategy -> ListenStrategy.ONE to listOfNotNull(listenTo.one)
            is AnyEventConsumptionStrategy -> ListenStrategy.ANY to (listenTo.any ?: emptyList())
            is AllEventConsumptionStrategy -> ListenStrategy.ALL to (listenTo.all ?: emptyList())
            else -> return null
        }

        // Get readAs with default
        val readAs = listenTask.listen?.read ?: ListenAndReadAs.DATA

        return ListenTaskConfig(strategy, readAs, filters)
    }

    /**
     * Finds the index of the filter that matches the event (for ALL strategy).
     *
     * @return The filter index or null if no match found
     */
    private fun findMatchingFilterIndex(
        event: CloudEvent,
        filters: List<io.serverlessworkflow.api.types.EventFilter>
    ): Int? {
        for ((index, filter) in filters.withIndex()) {
            if (filterMatchesEvent(filter, event)) {
                return index
            }
        }
        return null
    }

    /**
     * Checks if a filter matches the event (simplified matching for filter index detection).
     */
    private fun filterMatchesEvent(
        filter: io.serverlessworkflow.api.types.EventFilter,
        event: CloudEvent
    ): Boolean {
        val props = filter.with ?: return true

        // Check type (most common and fastest check)
        props.type?.let { if (it != event.type) return false }

        // Check other literal fields
        props.id?.let { if (it != event.id) return false }
        props.subject?.let { if (it != event.subject) return false }
        props.datacontenttype?.let { if (it != event.dataContentType) return false }
        props.source?.get()?.toString()?.let { if (it != event.source?.toString()) return false }

        return true
    }

    /**
     * Handles ONE strategy: Complete immediately on first match.
     */
    private suspend fun handleOneMatch(
        event: CloudEvent,
        listener: ListenerModel,
        readAs: ListenAndReadAs
    ): Boolean {
        val eventData = extractEventContent(event, readAs)
        completeListener(listener, eventData)
        return true
    }

    /**
     * Handles ANY strategy: Complete on first match, or accumulate if `until` is set.
     */
    private suspend fun handleAnyMatch(
        event: CloudEvent,
        listener: ListenerModel,
        readAs: ListenAndReadAs
    ): Boolean {
        val eventData = extractEventContent(event, readAs)

        // If no accumulated events yet, complete immediately (no `until` support yet)
        // TODO: Add `until` condition support
        completeListener(listener, eventData)
        return true
    }

    /**
     * Handles ALL strategy: Track which filters have been matched.
     * Complete when all filters have at least one matching event.
     */
    private suspend fun handleAllMatch(
        event: CloudEvent,
        listener: ListenerModel,
        readAs: ListenAndReadAs,
        totalFilters: Int,
        matchedFilterIndex: Int
    ): Boolean {
        val eventData = extractEventContent(event, readAs)

        // Parse current matched indices
        val currentIndices = listener.matchedFilterIndices?.let {
            Json.decodeFromString<List<Int>>(it).toMutableSet()
        } ?: mutableSetOf()

        // Add the newly matched index
        currentIndices.add(matchedFilterIndex)

        // Check if all filters are now matched
        if (currentIndices.size >= totalFilters) {
            // All filters matched - complete with collected data
            // For simplicity, complete with the last event that completed the set
            completeListener(listener, eventData)
            return true
        }

        // Not complete yet - update progress
        listenerRepository.updateProgress(
            id = listener.id,
            accumulatedEvents = listener.accumulatedEvents,
            matchedFilterIndices = Json.encodeToString(currentIndices.toList()),
            correlationValues = listener.correlationValues
        )

        logger.debug {
            "Updated ALL progress for listener ${listener.id}: " +
                "${currentIndices.size}/$totalFilters filters matched"
        }
        return true
    }

    /**
     * Accumulates an event into the listener's accumulated events array.
     */
    @Suppress("unused")
    private fun accumulateEvent(listener: ListenerModel, eventData: JsonElement): JsonArray {
        val existing = listener.accumulatedEvents?.let {
            Json.decodeFromString<JsonArray>(it)
        } ?: JsonArray(emptyList())

        return JsonArray(existing + eventData)
    }

    /**
     * Extracts the event content based on the readAs mode.
     *
     * @param event The CloudEvent
     * @param readAs The extraction mode (DATA, ENVELOPE, RAW)
     * @return The extracted content as JsonElement
     */
    private fun extractEventContent(event: CloudEvent, readAs: ListenAndReadAs): JsonElement {
        return when (readAs) {
            ListenAndReadAs.DATA -> {
                // Extract just the data payload
                event.data?.let { data ->
                    try {
                        val bytes = data.toBytes()
                        if (bytes.isNotEmpty()) {
                            Json.parseToJsonElement(String(bytes))
                        } else {
                            JsonNull
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse CloudEvent data as JSON, using null" }
                        JsonNull
                    }
                } ?: JsonNull
            }

            ListenAndReadAs.ENVELOPE -> {
                // Return the full event structure
                buildJsonObject {
                    put("specversion", event.specVersion.toString())
                    put("id", event.id)
                    put("type", event.type)
                    event.source?.let { put("source", it.toString()) }
                    event.subject?.let { put("subject", it) }
                    event.time?.let { put("time", it.toString()) }
                    event.dataContentType?.let { put("datacontenttype", it) }
                    event.dataSchema?.let { put("dataschema", it.toString()) }
                    // Add data
                    event.data?.let { data ->
                        try {
                            val parsed = Json.parseToJsonElement(String(data.toBytes()))
                            put("data", parsed)
                        } catch (e: Exception) {
                            put("data", JsonNull)
                        }
                    }
                }
            }

            ListenAndReadAs.RAW -> {
                // Return raw bytes as base64 or string
                event.data?.let { data ->
                    JsonPrimitive(String(data.toBytes()))
                } ?: JsonNull
            }
        }
    }

    /**
     * Completes a listener by marking it done and emitting a resume command.
     *
     * @param listener The listener to complete
     * @param eventData The event data to pass to the workflow
     */
    private suspend fun completeListener(listener: ListenerModel, eventData: JsonElement) {
        // Mark listener as completed in database
        listenerRepository.markCompleted(listener.id)

        // Create resume command from the original ListenStarted state
        val listenStarted = listener.instanceMessage.workflowState
        val resumeCommand = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = listenStarted.nodeStack,
            rawOutput = eventData
        )

        // Emit the resume command to continue the workflow
        val resumeMessage = InstanceMessage(
            workflowInfo = listener.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        val idempotentKey = listenStarted.nodeStack.deriveIdempotentId("-listen-complete")

        // Use the emitter's send method which handles serialization correctly
        commandEmitter.send(resumeMessage, idempotentKey)

        logger.info {
            "Listener ${listener.id} completed for workflow ${listener.workflowId} " +
                "at position ${listener.workflowPosition}"
        }
    }
}
