// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.listeners.DefinitionListenService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection

/**
 * Service for managing workflow definitions and their associated listen tasks.
 *
 * This service coordinates operations between:
 * - [DefinitionRepository] for storing workflow definitions
 * - [DefinitionListenService] for extracting and storing listen task definitions
 * - [WorkflowCache] for caching parsed workflows
 *
 * ## Usage
 *
 * When creating or updating a definition:
 * ```kotlin
 * definitionService.save(model, workflow)
 * ```
 *
 * When deleting a definition:
 * ```kotlin
 * definitionService.delete(namespace, name, version)
 * ```
 *
 * ## Important
 *
 * All definition mutations should go through this service to ensure
 * listen definitions are kept in sync. Do not use [DefinitionRepository]
 * directly for insert/update/delete operations.
 */
@ApplicationScoped
class DefinitionService {

    private val logger = logger()

    @Inject
    private lateinit var definitionRepository: DefinitionRepository

    @Inject
    private lateinit var definitionListenService: DefinitionListenService

    @Inject
    private lateinit var databaseConfig: DatabaseConfig

    /**
     * Saves a workflow definition and extracts its listen tasks.
     *
     * @param model The definition model to save
     * @param force If true, update existing definition; if false, insert only
     * @return SaveResult indicating success and whether it was created or updated
     */
    suspend fun save(model: DefinitionModel, force: Boolean = false): SaveResult {

        val result = when (definitionRepository.insert(model)) {
            1 -> {
                logger.info { "Created workflow definition: ${model.namespace}/${model.name}:${model.version}" }
                SaveResult.CREATED
            }

            0 -> if (force) {
                when (definitionRepository.update(model)) {
                    1 -> {
                        logger.info { "Updated workflow definition: ${model.namespace}/${model.name}:${model.version}" }
                        SaveResult.UPDATED
                    }

                    else -> SaveResult.FAILED
                }
            } else {
                SaveResult.ALREADY_EXISTS
            }

            else -> SaveResult.FAILED
        }

        // Parse and cache the workflow definition
        // Note: Listen task definitions are retrieved on-demand from the cached workflow,
        // so no database sync is needed here.
        if (result == SaveResult.CREATED || result == SaveResult.UPDATED) {
            WorkflowCache.parseYamlAndPut(model.definition)
        }

        return result
    }

    // ==================== Read Operations ====================

    /**
     * Lists all workflow definitions across all namespaces, sorted by namespace, name, version.
     *
     * @param connection Optional database connection for transaction support
     * @return List of all definition models
     */
    suspend fun listAll(
        connection: Connection? = null
    ): List<DefinitionModel> = definitionRepository.listAll(connection)

    /**
     * Finds a specific workflow definition by namespace, name, and version.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @param connection Optional database connection for transaction support
     * @return The definition model if found, null otherwise
     */
    suspend fun findByNameAndVersion(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        connection: Connection? = null
    ): DefinitionModel? = definitionRepository.findByNameAndVersion(namespace, name, version, connection)

    /**
     * Lists all versions of a workflow by namespace and name.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param connection Optional database connection for transaction support
     * @return List of definition models for all versions
     */
    suspend fun listByName(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        connection: Connection? = null
    ): List<DefinitionModel> = definitionRepository.listByName(namespace, name, connection)

    /**
     * Lists all workflow definitions in a namespace.
     *
     * @param namespace The workflow namespace
     * @param connection Optional database connection for transaction support
     * @return List of all definition models in the namespace
     */
    suspend fun listAllInNamespace(
        namespace: WorkflowNamespace,
        connection: Connection? = null
    ): List<DefinitionModel> = definitionRepository.listAllInNamespace(namespace, connection)

    /**
     * Counts all workflow definitions in a namespace.
     *
     * @param namespace The workflow namespace
     * @param connection Optional database connection for transaction support
     * @return Count of definitions in the namespace
     */
    suspend fun countAllInNamespace(
        namespace: WorkflowNamespace,
        connection: Connection? = null
    ): Long = definitionRepository.countAllInNamespace(namespace, connection)

    // ==================== Delete Operations ====================

    /**
     * Deletes a specific workflow definition and its associated listen tasks.
     * This operation is transactional.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @param connection Optional database connection for transaction support
     * @return DeleteResult indicating success or not found
     */
    suspend fun delete(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        connection: Connection? = null
    ): DeleteResult {
        logger.debug { "Deleting workflow definition: $namespace/$name:$version" }

        return databaseConfig.withTransaction(connection) { conn ->
            // Check if workflow exists
            val model = definitionRepository.findByNameAndVersion(namespace, name, version, conn)
                ?: return@withTransaction DeleteResult.NotFound("Workflow '$name' version '$version' not found")

            // Remove active listeners for this workflow definition
            definitionListenService.removeListeners(namespace, name, version)

            // Remove from definition cache
            WorkflowCache.remove(namespace, name, version)

            // Delete the definition
            val deleted = definitionRepository.delete(model, conn) == 1
            if (deleted) {
                DeleteResult.Deleted(1, "workflow '$name' version '$version'")
            } else {
                DeleteResult.NotFound("Workflow '$name' version '$version' was deleted concurrently")
            }
        }
    }

    /**
     * Deletes all versions of a workflow and their associated listen tasks.
     * This operation is transactional.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param connection Optional database connection for transaction support
     * @return DeleteResult with count and version details
     */
    suspend fun deleteAllVersions(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        connection: Connection? = null
    ): DeleteResult = databaseConfig.withTransaction(connection) { conn ->

        val definitions = definitionRepository.listByName(namespace, name, conn)

        if (definitions.isEmpty()) {
            return@withTransaction DeleteResult.NotFound("No workflows found with name '$name'")
        }

        val versionsString = definitions.joinToString { it.version.toString() }

        definitions.forEach { model ->
            // Remove active listeners for each version
            definitionListenService.removeListeners(namespace, name, model.version)
            // Remove from cache
            WorkflowCache.remove(namespace, name, model.version)
        }

        val deletedCount = definitionRepository.delete(definitions, conn)
        DeleteResult.Deleted(
            deletedCount,
            "${definitions.size} versions ($versionsString) of workflow '$name'"
        )
    }

    /**
     * Deletes all workflow definitions in a namespace and their associated listen tasks.
     * This operation is transactional.
     *
     * @param namespace The workflow namespace
     * @param connection Optional database connection for transaction support
     * @return DeleteResult with count
     */
    suspend fun deleteAllInNamespace(
        namespace: WorkflowNamespace,
        connection: Connection? = null
    ): DeleteResult = databaseConfig.withTransaction(connection) { conn ->
        val count = definitionRepository.countAllInNamespace(namespace, conn)
        if (count == 0L) {
            return@withTransaction DeleteResult.NotFound("No workflows found in namespace '$namespace'")
        }

        val definitions = definitionRepository.listAllInNamespace(namespace, conn)

        definitions.forEach { model ->
            // Remove active listeners for each definition
            definitionListenService.removeListeners(namespace, model.name, model.version)
            // Remove from cache
            WorkflowCache.remove(namespace, model.name, model.version)
        }

        val deletedCount = definitionRepository.deleteAllInNamespace(namespace, conn)
        DeleteResult.Deleted(deletedCount, "$deletedCount workflows")
    }

    /**
     * Result of a save operation.
     */
    enum class SaveResult {
        /** Definition was created successfully */
        CREATED,

        /** Definition was updated successfully */
        UPDATED,

        /** Definition already exists and force=false */
        ALREADY_EXISTS,

        /** Operation failed */
        FAILED
    }

    /**
     * Result of a delete operation.
     */
    sealed class DeleteResult {
        /** Successfully deleted definitions */
        data class Deleted(
            val count: Int,
            val details: String? = null
        ) : DeleteResult()

        /** No definitions found to delete */
        data class NotFound(val message: String) : DeleteResult()
    }
}
