// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cleaner

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.ListenerRepository
import com.lemline.runner.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

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
@ExperimentalSerializationApi
internal class ListenerCleaner : AbstractCleaner<ListenerModel>() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var cleanerRepository: ListenerRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val crudRepository: WithCrudRepository<ListenerModel> get() = cleanerRepository

    /** Is this cleaner enabled? */
    override val enabled by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.enabled()?.getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    /** Cleanup configuration */
    override val cleanerConf by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.cleanup()
            ?: lemlineConfig.outbox().wait().cleanup() // Fallback to wait config
    }
}
