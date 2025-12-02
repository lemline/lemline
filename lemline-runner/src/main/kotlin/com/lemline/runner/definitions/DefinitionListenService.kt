// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.repositories.DefinitionListenRepository
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

/**
 * Service for managing listen task filter definitions extracted from workflows.
 *
 * This service provides a high-level interface for:
 * - Extracting and storing listen filters when definitions are created/updated
 * - Removing listen filters when definitions are deleted
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
    private lateinit var repository: DefinitionListenRepository

    @Inject
    private lateinit var cache: DefinitionListenCache

    /**
     * Extracts listen task filters from a workflow and synchronizes them
     * to the database and cache.
     *
     * This method:
     * 1. Removes any existing listen definitions for this workflow
     * 2. Extracts new listen definitions from the workflow
     * 3. Inserts them into the database
     * 4. Updates the cache
     *
     * @param workflow The parsed workflow definition
     * @return Number of listen filters extracted and stored
     */
    suspend fun syncListenDefinitions(workflow: Workflow): Int {
        val namespace = WorkflowNamespace(workflow.document.namespace)
        val name = WorkflowName(workflow.document.name)
        val version = WorkflowVersion(workflow.document.version)

        logger.debug { "Syncing listen definitions for $namespace/$name:$version" }

        // Remove existing definitions (from DB and cache)
        removeListenDefinitions(namespace, name, version)

        // Extract new definitions
        val filters = extractor.extract(workflow)
        if (filters.isEmpty()) {
            logger.debug { "No listen tasks found in $namespace/$name:$version" }
            return 0
        }

        // Insert into database
        repository.insert(filters)

        // Update cache
        cache.addFilters(filters)

        logger.info { "Synced ${filters.size} listen filters for $namespace/$name:$version" }
        return filters.size
    }

    /**
     * Removes all listen definitions for a workflow from the database and cache.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @return Number of filters removed
     */
    suspend fun removeListenDefinitions(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Int {
        logger.debug { "Removing listen definitions for $namespace/$name:$version" }

        // Remove from cache
        cache.removeByDefinition(namespace, name, version)

        // Remove from database (CASCADE will handle FK references if any)
        val deleted = repository.deleteByDefinition(namespace, name, version)

        if (deleted > 0) {
            logger.debug { "Removed $deleted listen filters for $namespace/$name:$version" }
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
