// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.values.WorkflowInfo
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.scheduled.AbstractScheduledTask
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/** Refresh definition routing, including changed and deleted definitions, across workers. */
@Startup
@ApplicationScoped
class DefinitionCacheSync : AbstractScheduledTask() {
    @Inject lateinit var definitionConfig: DefinitionConfig
    @Inject lateinit var definitionRepository: DefinitionRepository
    private val mutex = Mutex()
    @Volatile private var initialized = false

    override val jobName = "Definition cache sync"
    override val enabled: Boolean by lazy { definitionConfig.enabled }
    override val interval: Duration by lazy { definitionConfig.syncEvery }
    override val initialDelay: Duration = Duration.ZERO

    /** Do not acknowledge the first event against an empty, not-yet-loaded catalog. */
    suspend fun ensureInitialized() {
        if (!initialized) mutex.withLock { if (!initialized) reconcile() }
    }

    override suspend fun doWork() = refresh()

    suspend fun refresh() = mutex.withLock { reconcile() }

    private suspend fun reconcile() {
        do {
            val snapshot = WorkflowCache.snapshot()
            val definitions = definitionRepository.listAll().associate {
                WorkflowInfo(it.namespace, it.name, it.version) to it.definition
            }
            val applied = WorkflowCache.reconcile(snapshot, definitions) { info, error ->
                logger.warn(error) { "Failed to cache definition: $info" }
            }
            // A concurrent local write invalidates the database snapshot: query again.
        } while (!applied)
        initialized = true
    }
}
