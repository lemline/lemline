// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.core.workflows.DefinitionCache
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.scheduled.AbstractScheduledTask
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

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
 * @see DefinitionCache for workflow definition caching
 * @see DefinitionListenService for how listen tasks use the cache
 */
@Startup
@ApplicationScoped
@ExperimentalTime
internal class DefinitionCacheSync : AbstractScheduledTask() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var definitionRepository: DefinitionRepository

    override val taskName = "Definition cache sync"

    /** Enabled when CloudEvents consumer is enabled (listen feature). */
    override val enabled by lazy {
        lemlineConfig.messaging().cloudevents().getOrNull()?.consumer()?.enabled() ?: false
    }

    /** Sync interval (default: 10s) */
    override val interval: Duration by lazy {
        lemlineConfig.definitions().getOrNull()?.cache()?.getOrNull()?.syncEvery ?: 10.seconds
    }

    /**
     * Load all definitions from the database and ensure they're in the cache.
     */
    override suspend fun doWork() {
        val allDefinitions = definitionRepository.listAll()
        var loaded = 0
        var skipped = 0

        for (definition in allDefinitions) {
            // Skip if already cached
            if (DefinitionCache.getWorkflow(definition.namespace, definition.name, definition.version) != null) {
                skipped++
                continue
            }

            try {
                DefinitionCache.parseAndPut(definition.definition)
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
