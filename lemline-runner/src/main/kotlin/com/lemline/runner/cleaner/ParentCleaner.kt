// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cleaner

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.models.ParentModel
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * ParentCleaner specializes AbstractCleaner to clean up old parent waiting records.
 *
 * Parent waiting records are created when a workflow spawns a child workflow and waits for its completion.
 * When the child completes, the parent record is marked as SENT and scheduled for cleanup.
 * This cleaner removes old SENT records to prevent database bloat.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ParentCleaner : AbstractCleaner<ParentModel>() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var cleanerRepository: ParentRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val crudRepository: WithCrudRepository<ParentModel> get() = cleanerRepository

    // Is this cleaner enabled?
    override val enabled by lazy {
        lemlineConfig.outbox().parent().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    // Cleanup configuration
    override val cleanerConf by lazy { lemlineConfig.outbox().parent().cleanup() }
}
