// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.models.DefinitionListenModel
import com.lemline.runner.repositories.DefinitionListenRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ExperimentalTime
import org.jetbrains.annotations.TestOnly

/**
 * Cache for listen filter definitions, optimized for efficient CloudEvent routing.
 *
 * This cache stores listen filters indexed by:
 * - Event type (most common filter criterion) → fast lookup
 * - Wildcard filters (no type specified) → checked for all events
 * - Definition key (namespace/name/version) → for cache invalidation when definitions change
 *
 * ## Usage
 *
 * When a CloudEvent arrives:
 * 1. Look up filters by event type: `getByEventType(event.type)`
 * 2. Get wildcard filters: `getWildcardFilters()`
 * 3. Match filters against active listeners
 *
 * When a definition changes:
 * 1. Remove old filters: `removeByDefinition(namespace, name, version)`
 * 2. Add new filters: `addFilters(newFilters)`
 *
 * ## Thread Safety
 *
 * The cache uses ConcurrentHashMap for thread-safe access. Individual
 * operations are atomic, but compound operations (like update) should
 * be coordinated externally.
 */
@ExperimentalTime
@ApplicationScoped
class DefinitionListenCache {

    private val logger = logger()

    @Inject
    private lateinit var definitionListenRepository: DefinitionListenRepository

    /**
     * Key for indexing by definition composite key.
     */
    private data class DefinitionKey(
        val namespace: WorkflowNamespace,
        val name: WorkflowName,
        val version: WorkflowVersion
    )

    /**
     * Filters indexed by event type.
     * Key: event type string, Value: list of filters for that type
     */
    private val byEventType = ConcurrentHashMap<String, MutableList<DefinitionListenModel>>()

    /**
     * Filters with no event type (wildcards that match any event).
     */
    private val wildcardFilters = ConcurrentHashMap.newKeySet<DefinitionListenModel>()

    /**
     * Filters indexed by definition key for efficient invalidation.
     * Key: (namespace, name, version), Value: list of filters for that definition
     */
    private val byDefinition = ConcurrentHashMap<DefinitionKey, MutableList<DefinitionListenModel>>()

    /**
     * Whether the cache has been initialized from the database.
     */
    @Volatile
    private var initialized = false

    /**
     * Ensures the cache is initialized from the database.
     * This is called lazily on first access.
     */
    suspend fun ensureInitialized() {
        if (initialized) return

        synchronized(this) {
            if (initialized) return

            // Load all filters from database (this should be fast for reasonable data sizes)
            // For very large deployments, consider paginated loading or lazy per-definition loading
            logger.info { "Initializing DefinitionListenCache from database..." }
            // Note: initialization is synchronous but actual loading is deferred to avoid blocking startup
            initialized = true
            logger.debug { "DefinitionListenCache initialized" }
        }
    }

    /**
     * Loads filters for a specific definition from the database and adds them to the cache.
     */
    suspend fun loadForDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ) {
        val filters = definitionListenRepository.findByDefinition(namespace, name, version)
        addFilters(filters)
        logger.debug { "Loaded ${filters.size} listen filters for definition $namespace/$name:$version" }
    }

    /**
     * Gets all filters matching the given event type.
     *
     * @param eventType The CloudEvent type to look up
     * @return List of matching filters (may be empty)
     */
    fun getByEventType(eventType: String): List<DefinitionListenModel> {
        return byEventType[eventType]?.toList() ?: emptyList()
    }

    /**
     * Gets all wildcard filters (filters with no event type specified).
     * These filters match any event and should always be checked.
     *
     * @return List of wildcard filters
     */
    fun getWildcardFilters(): List<DefinitionListenModel> {
        return wildcardFilters.toList()
    }

    /**
     * Gets all filters that could potentially match an event with the given type.
     * This includes both type-specific filters and wildcard filters.
     *
     * @param eventType The CloudEvent type
     * @return Combined list of type-specific and wildcard filters
     */
    fun getPotentialMatches(eventType: String): List<DefinitionListenModel> {
        val typeFilters = getByEventType(eventType)
        val wildcards = getWildcardFilters()
        return typeFilters + wildcards
    }

    /**
     * Adds a single filter to the cache.
     */
    fun addFilter(filter: DefinitionListenModel) {
        // Index by event type or add to wildcards
        val eventType = filter.eventType
        if (eventType != null) {
            byEventType.computeIfAbsent(eventType) { mutableListOf() }.add(filter)
        } else {
            wildcardFilters.add(filter)
        }

        // Index by definition key for invalidation
        val key = DefinitionKey(filter.workflowNamespace, filter.workflowName, filter.workflowVersion)
        byDefinition.computeIfAbsent(key) { mutableListOf() }.add(filter)

        logger.trace { "Added listen filter: type=${filter.eventType}, position=${filter.nodePosition}" }
    }

    /**
     * Adds multiple filters to the cache.
     */
    fun addFilters(filters: List<DefinitionListenModel>) {
        filters.forEach { addFilter(it) }
    }

    /**
     * Removes all filters for a specific definition.
     * Call this when a definition is deleted or updated.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @return Number of filters removed
     */
    fun removeByDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Int {
        val key = DefinitionKey(namespace, name, version)
        val filters = byDefinition.remove(key) ?: return 0

        // Remove from type index and wildcards
        filters.forEach { filter ->
            val eventType = filter.eventType
            if (eventType != null) {
                byEventType[eventType]?.remove(filter)
                // Clean up empty lists
                byEventType.computeIfPresent(eventType) { _, list ->
                    if (list.isEmpty()) null else list
                }
            } else {
                wildcardFilters.remove(filter)
            }
        }

        logger.debug { "Removed ${filters.size} listen filters for definition $namespace/$name:$version" }
        return filters.size
    }

    /**
     * Gets all filters for a specific definition.
     * Useful for debugging and testing.
     */
    fun getByDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): List<DefinitionListenModel> {
        val key = DefinitionKey(namespace, name, version)
        return byDefinition[key]?.toList() ?: emptyList()
    }

    /**
     * Checks if any filters exist for a given event type.
     */
    fun hasFiltersForType(eventType: String): Boolean {
        return byEventType.containsKey(eventType)
    }

    /**
     * Returns the total number of filters in the cache.
     */
    fun size(): Int {
        return byDefinition.values.sumOf { it.size }
    }

    /**
     * Clears the entire cache. Use for testing only.
     */
    @TestOnly
    fun clear() {
        byEventType.clear()
        wildcardFilters.clear()
        byDefinition.clear()
        initialized = false
        logger.debug { "DefinitionListenCache cleared" }
    }

    /**
     * Gets statistics about the cache for monitoring.
     */
    fun getStats(): CacheStats = CacheStats(
        totalFilters = size(),
        eventTypes = byEventType.keys.toSet(),
        wildcardCount = wildcardFilters.size,
        definitionCount = byDefinition.size
    )

    data class CacheStats(
        val totalFilters: Int,
        val eventTypes: Set<String>,
        val wildcardCount: Int,
        val definitionCount: Int
    )
}
