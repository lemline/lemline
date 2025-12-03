// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.models.DefinitionListenFilterModel
import com.lemline.runner.models.DefinitionListenModel
import com.lemline.runner.repositories.DefinitionListenFilterRepository
import com.lemline.runner.repositories.DefinitionListenRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ExperimentalTime
import org.jetbrains.annotations.TestOnly

/**
 * Cache for listen task and filter definitions, optimized for efficient CloudEvent routing.
 *
 * This cache stores:
 * - Listen tasks indexed by ID (for looking up strategy/readAs)
 * - Filters indexed by event type (most common filter criterion) → fast lookup
 * - Wildcard filters (no type specified) → checked for all events
 * - Both indexed by definition key (namespace/name/version) → for cache invalidation
 *
 * ## Usage
 *
 * When a CloudEvent arrives:
 * 1. Look up filters by event type: `getFiltersByEventType(event.type)`
 * 2. Get wildcard filters: `getWildcardFilters()`
 * 3. For each matching filter, get the listen task: `getListenTask(filter.listenId)`
 *
 * When a definition changes:
 * 1. Remove old entries: `removeByDefinition(namespace, name, version)`
 * 2. Add new entries: `addListenTasks(extractedTasks)`
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

    @Inject
    private lateinit var definitionListenFilterRepository: DefinitionListenFilterRepository

    /**
     * Key for indexing by definition composite key.
     */
    private data class DefinitionKey(
        val namespace: WorkflowNamespace,
        val name: WorkflowName,
        val version: WorkflowVersion
    )

    /**
     * Listen tasks indexed by ID.
     */
    private val listenTasksById = ConcurrentHashMap<IDV7, DefinitionListenModel>()

    /**
     * Filters indexed by event type.
     * Key: event type string, Value: list of filters for that type
     */
    private val filtersByEventType = ConcurrentHashMap<String, MutableList<DefinitionListenFilterModel>>()

    /**
     * Filters with no event type (wildcards that match any event).
     */
    private val wildcardFilters = ConcurrentHashMap.newKeySet<DefinitionListenFilterModel>()

    /**
     * Listen tasks indexed by definition key for efficient invalidation.
     */
    private val listenTasksByDefinition = ConcurrentHashMap<DefinitionKey, MutableList<DefinitionListenModel>>()

    /**
     * Filters indexed by definition key for efficient invalidation.
     */
    private val filtersByDefinition = ConcurrentHashMap<DefinitionKey, MutableList<DefinitionListenFilterModel>>()

    /**
     * Filters indexed by listen task ID for efficient count lookup (used for ALL strategy).
     */
    private val filtersByListenId = ConcurrentHashMap<IDV7, MutableList<DefinitionListenFilterModel>>()

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
     * Loads listen tasks and filters for a specific definition from the database and adds them to the cache.
     */
    suspend fun loadForDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ) {
        val listenTasks = definitionListenRepository.findByDefinition(namespace, name, version)
        var totalFilters = 0

        listenTasks.forEach { listenTask ->
            addListenTask(listenTask, namespace, name, version)
            val filters = definitionListenFilterRepository.findByListenId(listenTask.id)
            filters.forEach { filter ->
                addFilter(filter, namespace, name, version)
            }
            totalFilters += filters.size
        }

        logger.debug { "Loaded ${listenTasks.size} listen tasks with $totalFilters filters for definition $namespace/$name:$version" }
    }

    /**
     * Gets a listen task by ID.
     *
     * @param listenId The listen task ID
     * @return The listen task if found, null otherwise
     */
    fun getListenTask(listenId: IDV7): DefinitionListenModel? {
        return listenTasksById[listenId]
    }

    /**
     * Gets the total number of filters for a listen task.
     * Used by ALL strategy to know when all filters have been matched.
     *
     * @param listenId The listen task ID
     * @return The total number of filters for this listen task
     */
    fun getFilterCountForListenTask(listenId: IDV7): Int {
        return filtersByListenId[listenId]?.size ?: 0
    }

    /**
     * Gets all filters matching the given event type.
     *
     * @param eventType The CloudEvent type to look up
     * @return List of matching filters (may be empty)
     */
    fun getFiltersByEventType(eventType: String): List<DefinitionListenFilterModel> {
        return filtersByEventType[eventType]?.toList() ?: emptyList()
    }

    /**
     * Gets all wildcard filters (filters with no event type specified).
     * These filters match any event and should always be checked.
     *
     * @return List of wildcard filters
     */
    fun getWildcardFilters(): List<DefinitionListenFilterModel> {
        return wildcardFilters.toList()
    }

    /**
     * Gets all filters that could potentially match an event with the given type.
     * This includes both type-specific filters and wildcard filters.
     *
     * @param eventType The CloudEvent type
     * @return Combined list of type-specific and wildcard filters
     */
    fun getPotentialMatches(eventType: String): List<DefinitionListenFilterModel> {
        val typeFilters = getFiltersByEventType(eventType)
        val wildcards = getWildcardFilters()
        return typeFilters + wildcards
    }

    /**
     * Adds extracted listen tasks and their filters to the cache.
     */
    fun addExtractedListenTasks(
        extractedTasks: List<ExtractedListenTask>,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ) {
        extractedTasks.forEach { extracted ->
            addListenTask(extracted.listenTask, namespace, name, version)
            extracted.filters.forEach { filter ->
                addFilter(filter, namespace, name, version)
            }
        }
    }

    /**
     * Adds a single listen task to the cache.
     */
    private fun addListenTask(
        listenTask: DefinitionListenModel,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ) {
        listenTasksById[listenTask.id] = listenTask

        val key = DefinitionKey(namespace, name, version)
        listenTasksByDefinition.computeIfAbsent(key) { mutableListOf() }.add(listenTask)

        logger.trace { "Added listen task: id=${listenTask.id}, strategy=${listenTask.strategy}, position=${listenTask.nodePosition}" }
    }

    /**
     * Adds a single filter to the cache.
     */
    private fun addFilter(
        filter: DefinitionListenFilterModel,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ) {
        // Index by event type or add to wildcards
        val eventType = filter.eventType
        if (eventType != null) {
            filtersByEventType.computeIfAbsent(eventType) { mutableListOf() }.add(filter)
        } else {
            wildcardFilters.add(filter)
        }

        // Index by definition key for invalidation
        val key = DefinitionKey(namespace, name, version)
        filtersByDefinition.computeIfAbsent(key) { mutableListOf() }.add(filter)

        // Index by listen task ID for filter count lookup (used by ALL strategy)
        filtersByListenId.computeIfAbsent(filter.listenId) { mutableListOf() }.add(filter)

        logger.trace { "Added listen filter: type=${filter.eventType}, listenId=${filter.listenId}" }
    }

    /**
     * Removes all listen tasks and filters for a specific definition.
     * Call this when a definition is deleted or updated.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @return Number of listen tasks removed
     */
    fun removeByDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Int {
        val key = DefinitionKey(namespace, name, version)

        // Remove listen tasks
        val listenTasks = listenTasksByDefinition.remove(key) ?: emptyList()
        listenTasks.forEach { listenTask ->
            listenTasksById.remove(listenTask.id)
        }

        // Remove filters
        val filters = filtersByDefinition.remove(key) ?: emptyList()
        filters.forEach { filter ->
            val eventType = filter.eventType
            if (eventType != null) {
                filtersByEventType[eventType]?.remove(filter)
                // Clean up empty lists
                filtersByEventType.computeIfPresent(eventType) { _, list ->
                    if (list.isEmpty()) null else list
                }
            } else {
                wildcardFilters.remove(filter)
            }
        }

        logger.debug { "Removed ${listenTasks.size} listen tasks and ${filters.size} filters for definition $namespace/$name:$version" }
        return listenTasks.size
    }

    /**
     * Gets all listen tasks for a specific definition.
     * Useful for debugging and testing.
     */
    fun getListenTasksByDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): List<DefinitionListenModel> {
        val key = DefinitionKey(namespace, name, version)
        return listenTasksByDefinition[key]?.toList() ?: emptyList()
    }

    /**
     * Gets all filters for a specific definition.
     * Useful for debugging and testing.
     */
    fun getFiltersByDefinition(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): List<DefinitionListenFilterModel> {
        val key = DefinitionKey(namespace, name, version)
        return filtersByDefinition[key]?.toList() ?: emptyList()
    }

    /**
     * Checks if any filters exist for a given event type.
     */
    fun hasFiltersForType(eventType: String): Boolean {
        return filtersByEventType.containsKey(eventType)
    }

    /**
     * Returns the total number of filters in the cache.
     */
    fun filterCount(): Int {
        return filtersByDefinition.values.sumOf { it.size }
    }

    /**
     * Returns the total number of listen tasks in the cache.
     */
    fun listenTaskCount(): Int {
        return listenTasksById.size
    }

    /**
     * Clears the entire cache. Use for testing only.
     */
    @TestOnly
    fun clear() {
        listenTasksById.clear()
        filtersByEventType.clear()
        wildcardFilters.clear()
        listenTasksByDefinition.clear()
        filtersByDefinition.clear()
        filtersByListenId.clear()
        initialized = false
        logger.debug { "DefinitionListenCache cleared" }
    }

    /**
     * Adds a filter to the cache for testing purposes.
     */
    @TestOnly
    fun addFilterForTesting(
        filter: DefinitionListenFilterModel,
        namespace: WorkflowNamespace = WorkflowNamespace("test"),
        name: WorkflowName = WorkflowName("test"),
        version: WorkflowVersion = WorkflowVersion("1.0.0")
    ) {
        addFilter(filter, namespace, name, version)
    }

    /**
     * Adds a listen task to the cache for testing purposes.
     */
    @TestOnly
    fun addListenTaskForTesting(
        listenTask: DefinitionListenModel,
        namespace: WorkflowNamespace = listenTask.workflowNamespace,
        name: WorkflowName = listenTask.workflowName,
        version: WorkflowVersion = listenTask.workflowVersion
    ) {
        addListenTask(listenTask, namespace, name, version)
    }

    /**
     * Gets statistics about the cache for monitoring.
     */
    fun getStats(): CacheStats = CacheStats(
        totalListenTasks = listenTaskCount(),
        totalFilters = filterCount(),
        eventTypes = filtersByEventType.keys.toSet(),
        wildcardCount = wildcardFilters.size,
        definitionCount = listenTasksByDefinition.size
    )

    data class CacheStats(
        val totalListenTasks: Int,
        val totalFilters: Int,
        val eventTypes: Set<String>,
        val wildcardCount: Int,
        val definitionCount: Int
    )
}
