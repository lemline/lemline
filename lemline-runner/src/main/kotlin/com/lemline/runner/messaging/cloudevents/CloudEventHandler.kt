// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.definitions.DefinitionListenCache
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.DefinitionListenFilterModel
import com.lemline.runner.models.DefinitionListenModel
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.ListenerRepository
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
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
 * 1. **Cache Lookup**: Find definition filters that match the event type
 * 2. **Listen Task Lookup**: Get the parent listen task for each filter
 * 3. **Listener Query**: Find active listeners for matching listen definitions
 * 4. **Filter Matching**: Check if the event matches the listener's filters
 * 5. **Strategy Handling**: Apply ONE/ANY/ALL strategy logic
 * 6. **Completion**: Resume matched workflows with the event data
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
    private val definitionListenCache: DefinitionListenCache,
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

        // Step 1: Find matching definition filters from cache
        val matchingFilters = definitionListenCache.getPotentialMatches(eventType)
        if (matchingFilters.isEmpty()) {
            logger.trace { "No definition filters match event type: $eventType" }
            return 0
        }

        logger.debug { "Found ${matchingFilters.size} potential definition filters for event type: $eventType" }

        // Step 2: Group filters by their parent listen task ID
        val filtersByListenId = matchingFilters.groupBy { it.listenId }

        var affectedCount = 0

        // Step 3: For each listen task, find and process matching listeners
        for ((listenId, filters) in filtersByListenId) {
            // Get the listen task definition from cache
            val listenTask = definitionListenCache.getListenTask(listenId)
            if (listenTask == null) {
                logger.warn { "Listen task $listenId not found in cache" }
                continue
            }

            // Find active listeners for this listen definition
            val listeners = listenerRepository.findByListenDefinitionId(listenId)
            if (listeners.isEmpty()) {
                continue
            }

            logger.debug { "Found ${listeners.size} active listeners for listen task $listenId" }

            // Process each listener
            for (listener in listeners) {
                val affected = processListenerMatch(event, listener, listenTask, filters)
                if (affected) affectedCount++
            }
        }

        logger.debug { "CloudEvent processing complete: $affectedCount listeners affected" }
        return affectedCount
    }

    /**
     * Processes a potential match between an event and a listener.
     *
     * @param event The incoming CloudEvent
     * @param listener The listener to check
     * @param listenTask The listen task definition
     * @param definitionFilters Definition-level filters for this listener's listen task
     * @return true if the listener was affected (completed or updated)
     */
    private suspend fun processListenerMatch(
        event: CloudEvent,
        listener: ListenerModel,
        listenTask: DefinitionListenModel,
        definitionFilters: List<DefinitionListenFilterModel>
    ): Boolean {
        // Find which filter index (if any) this event matches
        val matchedFilterIndex = findMatchingFilterIndex(event, definitionFilters)
        if (matchedFilterIndex == null) {
            logger.trace { "Event doesn't match any filter for listener ${listener.id}" }
            return false
        }

        logger.debug { "Event matches filter index $matchedFilterIndex for listener ${listener.id}" }

        // Apply strategy-specific logic
        // For ALL strategy, we need the TOTAL filter count, not just the count of filters matching this event
        val totalFilterCount = definitionListenCache.getFilterCountForListenTask(listenTask.id)

        return when (listenTask.strategy) {
            ListenStrategy.ONE -> handleOneMatch(event, listener, listenTask.readAs)
            ListenStrategy.ANY -> handleAnyMatch(event, listener, listenTask, totalFilterCount)
            ListenStrategy.ALL -> handleAllMatch(event, listener, listenTask, totalFilterCount, matchedFilterIndex)
        }
    }

    /**
     * Finds which filter index the event matches (if any).
     *
     * @return The matched filter's filterIndex from the definition, or null if no match
     */
    private fun findMatchingFilterIndex(
        event: CloudEvent,
        filters: List<DefinitionListenFilterModel>
    ): Int? {
        for (filter in filters) {
            // Check type match (null = wildcard)
            if (filter.eventType != null && filter.eventType != event.type) {
                continue
            }

            // Check source match if specified
            if (filter.eventSource != null && filter.eventSource != event.source?.toString()) {
                continue
            }

            // Check subject match if specified
            if (filter.eventSubject != null && filter.eventSubject != event.subject) {
                continue
            }

            // TODO: Add correlation checking here for Phase 8.3
            // For now, we skip correlation and match on type/source/subject only

            // Return the filter's defined index, not the list position
            return filter.filterIndex
        }
        return null
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
        listenTask: DefinitionListenModel,
        filterCount: Int
    ): Boolean {
        val eventData = extractEventContent(event, listenTask.readAs)

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
        listenTask: DefinitionListenModel,
        totalFilters: Int,
        matchedFilterIndex: Int
    ): Boolean {
        val eventData = extractEventContent(event, listenTask.readAs)

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
