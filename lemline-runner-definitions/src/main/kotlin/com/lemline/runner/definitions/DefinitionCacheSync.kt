// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.scheduled.AbstractScheduledTask
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Duration

/**
 * Scheduled process that ensures all workflow definitions from the database are loaded into DefinitionCache.
 *
 * ## Purpose
 *
 * This is critical for the "listen" feature which needs `DefinitionCache.getAllWorkflows()` to find
 * matching listeners for incoming CloudEvents. Without this sync process, the cache could be empty
 * at startup, causing CloudEvents to be ignored because no matching listen tasks are found.
 *
 * ## Design
 *
 * - Runs on a configurable schedule (default: every 10 seconds)
 * - Loads all definitions from the database and caches them
 * - Skips definitions that are already cached to avoid unnecessary parsing
 *
 * @see WorkflowCache for workflow definition caching
 * @see DefinitionListenService for how listen tasks use the cache
 */
@Startup
@ApplicationScoped
internal class DefinitionCacheSync : AbstractScheduledTask() {

    @Inject
    lateinit var definitionConfig: DefinitionConfig

    @Inject
    private lateinit var definitionRepository: DefinitionRepository

    override val jobName = "Definition cache sync"

    /** Enabled when CloudEvents consumer is enabled (listen feature). */
    override val enabled: Boolean by lazy { definitionConfig.enabled }

    /** Sync interval (default: 10s) */
    override val interval: Duration by lazy { definitionConfig.syncEvery }

    override val initialDelay: Duration = Duration.ZERO

    /**
     * Load all definitions from the database and ensure they're in the cache.
     */
    override suspend fun doWork() {
        val allDefinitions = definitionRepository.listAll()
        var loaded = 0
        var skipped = 0

        for (definition in allDefinitions) {
            // Skip if already cached
            if (WorkflowCache.getWorkflow(definition.namespace, definition.name, definition.version) != null) {
                skipped++
                continue
            }

            try {
                WorkflowCache.parseYamlAndPut(definition.definition)
                loaded++
                logger.debug { "Cached definition: ${definition.namespace}/${definition.name}:${definition.version}" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to cache definition: ${definition.namespace}/${definition.name}:${definition.version}" }
            }
        }

        if (loaded > 0) {
            logger.info { "Definition cache sync: loaded $loaded definition(s), skipped $skipped already cached" }
        } else {
            logger.trace { "Definition cache sync: all ${allDefinitions.size} definition(s) already cached" }
        }
    }
}
