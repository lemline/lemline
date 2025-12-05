// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.processors.ListenStrategy
import com.lemline.runner.definitions.DefinitionListenService
import com.lemline.runner.definitions.ListenerMatch
import com.lemline.runner.models.ListenerEventModel
import com.lemline.runner.repositories.ListenerEventRepository
import com.lemline.runner.repositories.ListenerRepository
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles incoming CloudEvents by matching them against active listeners
 * and marking matched listeners ready for completion.
 *
 * ## Processing Flow
 *
 * 1. **Service Query**: Use DefinitionListenService to find matching listeners with context
 * 2. **Batch Grouping**: Group matches by strategy and readAs mode
 * 3. **Strategy-Specific Processing**:
 *    - ONE/ANY: Single batch UPDATE on listeners table
 *    - ALL: Batch INSERT into events table + COUNT check + batch UPDATE
 * 4. **Completion**: ListenerCompletionOutbox sends resume commands
 *
 * ## Batch Optimization
 *
 * Instead of processing listeners one-by-one, this handler:
 * - Groups ONE/ANY listeners for single batch UPDATE
 * - Groups ALL listeners by (filterIndex, totalFilters, readAs) for batch INSERT
 * - Extracts event data once per readAs mode (not per listener)
 *
 * For 1000 listeners, this reduces database calls from 1000+ to just a few.
 *
 * ## Race Condition Prevention
 *
 * - ONE/ANY: Atomic UPDATE with WHERE guards prevents double-completion
 * - ALL: Idempotent INSERT (ON CONFLICT DO NOTHING) + atomic UPDATE prevents races
 *
 * @see ListenerCompletionOutbox for completion processing
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
internal class CloudEventHandler(
    private val definitionListenService: DefinitionListenService,
    private val listenerRepository: ListenerRepository,
) {
    private val logger = logger()

    @Inject
    private lateinit var listenerEventRepository: ListenerEventRepository

    /**
     * Processes an incoming CloudEvent by matching it against active listeners.
     *
     * Uses batch operations to efficiently handle thousands of listeners:
     * - ONE/ANY strategy: Single batch UPDATE on listeners table
     * - ALL strategy: Batch INSERT into events table + COUNT + batch UPDATE
     *
     * @param event The incoming CloudEvent
     * @return Number of listeners that were affected (completed or updated)
     */
    suspend fun handleCloudEvent(event: CloudEvent): Int {
        val eventType: String = event.type

        logger.debug { "Processing CloudEvent: type=$eventType, source=${event.source}, id=${event.id}" }

        // Find all matching listeners with full context for batch processing
        val matches = definitionListenService.findMatchingListeners(event)
        if (matches.isEmpty()) {
            logger.trace { "No matching listeners for event type: $eventType" }
            return 0
        }

        logger.debug { "Found ${matches.size} listener matches for event type: $eventType" }

        // Extract event data once per readAs mode (avoid repeated parsing)
        val eventDataByReadAs = matches.map { it.readAs }.toSet().associateWith { readAs ->
            extractEventContent(event, readAs)
        }

        var affectedCount = 0

        // Separate matches by strategy
        val oneAnyMatches = matches.filter { it.strategy in listOf(ListenStrategy.ONE, ListenStrategy.ANY) }
        val allMatches = matches.filter { it.strategy == ListenStrategy.ALL }

        // Process ONE/ANY - single batch UPDATE (no transaction needed, atomic UPDATEs)
        if (oneAnyMatches.isNotEmpty()) {
            affectedCount += processOneAny(oneAnyMatches, eventDataByReadAs)
        }

        // Process ALL - batch INSERT + COUNT + batch UPDATE
        if (allMatches.isNotEmpty()) {
            affectedCount += processAll(allMatches, eventDataByReadAs)
        }

        logger.debug { "CloudEvent processing complete: $affectedCount listeners affected" }
        return affectedCount
    }

    /**
     * Processes ONE/ANY strategy matches.
     *
     * Groups listeners by readAs mode and executes one batch UPDATE per group.
     * All listeners in a group get the same event data stored directly in the listeners table.
     *
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processOneAny(
        matches: List<ListenerMatch>,
        eventDataByReadAs: Map<ListenAndReadAs, JsonElement>,
    ): Int {
        // Group by readAs mode (different event data format)
        val byReadAs = matches.groupBy { it.readAs }

        var totalAffected = 0

        for ((readAs, matchGroup) in byReadAs) {
            val eventData = eventDataByReadAs[readAs] ?: continue
            // Store single event as JSON (will be wrapped in array at completion time)
            val eventJson = Json.encodeToString(eventData)

            // Get unique listener IDs (same listener might match multiple filters, but we only need one)
            val listenerIds = matchGroup.map { it.listener.id }.distinct()

            val affected = listenerRepository.batchMarkReadyForCompletion(
                ids = listenerIds,
                event = eventJson
            )

            if (affected > 0) {
                logger.info { "Batch marked $affected ONE/ANY listeners ready for completion (readAs=$readAs)" }
            }
            totalAffected += affected
        }

        return totalAffected
    }

    /**
     * Processes ALL strategy matches.
     *
     * For ALL strategy:
     * 1. Batch INSERT events into lemline_listener_events table (idempotent)
     * 2. Batch COUNT events per listener to check completion
     * 3. Batch UPDATE listeners that are now complete (COUNT = totalFilters)
     *
     * This approach:
     * - Uses insert-only pattern (no read-modify-write race conditions)
     * - Idempotent inserts via ON CONFLICT DO NOTHING
     * - Minimizes database requests (3 requests total regardless of listener count)
     *
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processAll(
        matches: List<ListenerMatch>,
        eventDataByReadAs: Map<ListenAndReadAs, JsonElement>,
    ): Int {
        // Group by (filterIndex, totalFilters, readAs) for batch insert
        data class AllGroupKey(val filterIndex: Int, val totalFilters: Int, val readAs: ListenAndReadAs)

        val byGroup = matches.groupBy { AllGroupKey(it.filterIndex, it.totalFilters, it.readAs) }

        // Build all event models for batch insert
        val eventModels = mutableListOf<ListenerEventModel>()
        val listenerTotalFilters = mutableMapOf<IDV7, Int>()

        for ((groupKey, matchGroup) in byGroup) {
            val eventData = eventDataByReadAs[groupKey.readAs] ?: continue
            val eventJson = Json.encodeToString(eventData)

            for (match in matchGroup) {
                val listenerId = match.listener.id

                // Derive idempotent ID for ALL: listener_id + filter_index
                val eventId = listenerId.derive("-filter-${groupKey.filterIndex}")

                eventModels.add(
                    ListenerEventModel(
                        id = eventId,
                        listenerId = listenerId,
                        filterIndex = groupKey.filterIndex,  // Explicit filter index for ALL
                        event = eventJson
                    )
                )

                // Track totalFilters for completion check
                listenerTotalFilters[listenerId] = groupKey.totalFilters
            }
        }

        if (eventModels.isEmpty()) return 0

        // Step 1: Batch INSERT events (idempotent - duplicates ignored)
        val listenerIds = listenerTotalFilters.keys.toList()
        val inserted = listenerEventRepository.insert(eventModels)
        logger.debug { "Inserted $inserted events for ALL strategy listeners" }

        // Step 2: Fetch all events and filter to completed listeners in code
        // This combines COUNT + FETCH into a single query - count is derived from list.size()
        val eventsByListener = listenerEventRepository.batchFindByListenerIds(listenerIds)

        // Step 3: Filter to completed listeners and aggregate events into JSON arrays
        val listenerEvents = eventsByListener
            .mapNotNull { (listenerId, events) ->
                val totalFilters = listenerTotalFilters[listenerId] ?: return@mapNotNull null
                if (events.size < totalFilters) return@mapNotNull null
                // Take only first totalFilters events (safety: handle race condition with extra events)
                // Events are already ordered by filter_index from the query
                val jsonArray = JsonArray(events.take(totalFilters).map { Json.parseToJsonElement(it.event) })
                listenerId to Json.encodeToString(jsonArray)
            }
            .toMap()

        if (listenerEvents.isEmpty()) {
            logger.debug { "No ALL listeners completed yet (filters still pending)" }
            return 0
        }

        // Step 4: Batch UPDATE completed listeners with aggregated events
        val completed = listenerRepository.batchMarkReadyForCompletionFromEvents(listenerEvents)

        if (completed > 0) {
            logger.info { "Batch marked $completed ALL listeners ready for completion (all filters matched)" }
        }

        return completed
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
                    // Explicitly set specversion to ensure it's present and first
                    put("specversion", event.specVersion.toString())

                    // Dynamically add all other context attributes (standard + extensions)
                    event.attributeNames.forEach { name ->
                        // Skip specversion if it appears in attributes to avoid redundancy
                        if (name == "specversion") return@forEach

                        when (val value = event.getAttribute(name)) {
                            is Number -> put(name, value)
                            is Boolean -> put(name, value)
                            // Handle URIs, Time, Strings, etc. via toString()
                            else -> value?.let { put(name, it.toString()) }
                        }
                    }

                    // Add data
                    event.data?.let { data ->
                        val parsed = runCatching {
                            Json.parseToJsonElement(String(data.toBytes()))
                        }.getOrElse { JsonNull }
                        put("data", parsed)
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
}
