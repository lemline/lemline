// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners.cleaner

import com.lemline.runner.common.cleaner.AbstractCleaner
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.listeners.ListenerConfig
import com.lemline.runner.listeners.ListenerModel
import com.lemline.runner.listeners.ListenerRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

/**
 * Cleans up old completed and failed listener records.
 *
 * Listener records are created when a workflow starts listening for CloudEvents.
 * When a listener completes (receives matching events) or fails (timeout/error),
 * it is marked accordingly. This cleaner removes old completed/failed records
 * to prevent database bloat.
 *
 * The cleanup runs on a configurable schedule and removes records older than
 * the configured retention period.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
internal class ListenerCleaner : AbstractCleaner<ListenerModel>() {

    @Inject
    lateinit var listenerConfig: ListenerConfig

    @Inject
    override lateinit var cleanerRepository: ListenerRepository

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val crudRepository: WithCrudRepository<ListenerModel> get() = cleanerRepository

    /** Is this cleaner enabled? */
    override val enabled: Boolean by lazy { listenerConfig.enabled }

    /** Cleanup configuration */
    override val cleanerConfig: CleanupConfig by lazy { listenerConfig.cleanup }
}
