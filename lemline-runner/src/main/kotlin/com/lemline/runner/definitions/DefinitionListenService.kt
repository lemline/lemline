// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.common.values.name
import com.lemline.common.values.namespace
import com.lemline.common.values.version
import com.lemline.runner.repositories.DefinitionListenFilterRepository
import com.lemline.runner.repositories.DefinitionListenRepository
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

/**
 * Service for managing listen task definitions extracted from workflows.
 *
 * This service provides a high-level interface for:
 * - Extracting and storing listen tasks and filters when definitions are created/updated
 * - Removing listen definitions when definitions are deleted
 * - Keeping the cache in sync with the database
 *
 * ## Usage
 *
 * When a definition is saved (insert or update):
 * ```kotlin
 * definitionListenService.syncListenDefinitions(workflow)
 * ```
 *
 * When a definition is deleted:
 * ```kotlin
 * definitionListenService.removeListenDefinitions(namespace, name, version)
 * ```
 */
@ExperimentalTime
@ApplicationScoped
class DefinitionListenService {

    private val logger = logger()

    @Inject
    private lateinit var extractor: DefinitionListenExtractor

    @Inject
    private lateinit var listenRepository: DefinitionListenRepository

    @Inject
    private lateinit var filterRepository: DefinitionListenFilterRepository

    @Inject
    private lateinit var cache: DefinitionListenCache

    /**
     * Extracts listen tasks and filters from a workflow and synchronizes them
     * to the database and cache.
     *
     * This method:
     * 1. Removes any existing listen definitions for this workflow
     * 2. Extracts new listen tasks and filters from the workflow
     * 3. Inserts them into the database
     * 4. Updates the cache
     *
     * @param workflow The parsed workflow definition
     * @return Number of listen tasks extracted and stored
     */
    suspend fun syncListenDefinitions(workflow: Workflow): Int {
        val namespace = workflow.namespace
        val name = workflow.name
        val version = workflow.version

        logger.debug { "Syncing listen definitions for $namespace/$name:$version" }

        // Remove existing definitions (from DB and cache)
        removeListenDefinitions(namespace, name, version)

        // Extract new definitions
        val extractedTasks = extractor.extract(workflow)
        if (extractedTasks.isEmpty()) {
            logger.debug { "No listen tasks found in $namespace/$name:$version" }
            return 0
        }

        // Insert into database (listen tasks first, then filters)
        val listenTasks = extractedTasks.map { it.listenTask }
        val allFilters = extractedTasks.flatMap { it.filters }

        listenRepository.insert(listenTasks)
        if (allFilters.isNotEmpty()) {
            filterRepository.insert(allFilters)
        }

        // Update cache
        cache.addExtractedListenTasks(extractedTasks, namespace, name, version)

        logger.info { "Synced ${listenTasks.size} listen tasks with ${allFilters.size} filters for $namespace/$name:$version" }
        return listenTasks.size
    }

    /**
     * Removes all listen definitions for a workflow from the database and cache.
     * Deleting listen tasks will CASCADE to delete associated filters.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @return Number of listen tasks removed
     */
    suspend fun removeListenDefinitions(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Int {
        logger.debug { "Removing listen definitions for $namespace/$name:$version" }

        // Remove from cache
        cache.removeByDefinition(namespace, name, version)

        // Remove from database (CASCADE will delete filters too)
        val deleted = listenRepository.deleteByDefinition(namespace, name, version)

        if (deleted > 0) {
            logger.debug { "Removed $deleted listen tasks for $namespace/$name:$version" }
        }
        return deleted
    }

    /**
     * Loads listen definitions for a workflow from the database into the cache.
     * Useful for cache warming or recovery.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     */
    suspend fun loadIntoCache(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ) {
        cache.loadForDefinition(namespace, name, version)
    }
}
