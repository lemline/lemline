// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

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
 * Cleaner for schedule entities.
 * Deletes schedule records that have completed (no more executions) and exceeded their retention period.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
class ScheduleCleaner : AbstractCleaner<ScheduleModel>() {

    @Inject
    lateinit var scheduleConfig: ScheduleConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    override val cleanerRepository: WithCleanerRepository<ScheduleModel> get() = scheduleRepository

    override val crudRepository: WithCrudRepository<ScheduleModel> get() = scheduleRepository

    override val enabled by lazy { scheduleConfig.enabled }

    override val cleanerConfig: CleanupConfig by lazy { scheduleConfig.cleanup }

    override val jobName: String get() = "ScheduleCleaner"
}
