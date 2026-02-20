// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.runner.common.cleaner.AbstractCleaner
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * ForkCleaner specializes AbstractCleaner to clean up old fork waiting records.
 *
 * Fork waiting records are created when a workflow executes parallel branches in a fork.
 * When all branches complete (or first completes in compete mode), the fork record is marked
 * as SENT and scheduled for cleanup. This cleaner removes old SENT records to prevent database bloat.
 */
@Startup
@ApplicationScoped
class ForkCleaner : AbstractCleaner<ForkModel>() {

    @Inject
    lateinit var forkConfig: ForkFeatureConfig

    @Inject
    lateinit var forkRepository: ForkRepository

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val cleanerRepository: WithCleanerRepository<ForkModel> get() = forkRepository

    override val crudRepository: WithCrudRepository<ForkModel> get() = forkRepository

    // Is this cleaner enabled?
    override val enabled by lazy { forkConfig.enabled }

    // Cleanup configuration
    override val cleanerConfig: CleanupConfig by lazy { forkConfig.cleanup }

    override val jobName: String get() = "ForkCleaner"
}
