// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.waits

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
 * Cleaner for wait entities.
 * Deletes wait records that have completed or failed and exceeded their retention period.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
class WaitCleaner : AbstractCleaner<WaitModel>() {

    @Inject
    lateinit var waitConfig: WaitConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var waitRepository: WaitRepository

    override val cleanerRepository: WithCleanerRepository<WaitModel> get() = waitRepository

    override val crudRepository: WithCrudRepository<WaitModel> get() = waitRepository

    override val enabled by lazy { waitConfig.enabled }

    override val cleanerConfig: CleanupConfig by lazy { waitConfig.cleanup }

    override val jobName: String get() = "WaitCleaner"
}
