// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.runner.common.cleaner.AbstractCleaner
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

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
class ParentCleaner : AbstractCleaner<ParentModel>() {

    @Inject
    lateinit var parentConfig: ParentFeatureConfig

    @Inject
    lateinit var parentRepository: ParentRepository

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val cleanerRepository: WithCleanerRepository<ParentModel> get() = parentRepository

    override val crudRepository: WithCrudRepository<ParentModel> get() = parentRepository

    // Is this cleaner enabled?
    override val enabled by lazy { parentConfig.enabled }

    // Cleanup configuration
    override val cleanerConfig: CleanupConfig by lazy { parentConfig.cleanup }

    override val jobName: String get() = "ParentCleaner"
}
