// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.runner.common.cleaner.AbstractCleaner
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.listeners.ListenerConfig
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Cleans completed and failed event-triggered starts using listener retention settings. */
@Startup
@ApplicationScoped
internal class EventScheduleCleaner : AbstractCleaner<EventScheduleModel>() {

    @Inject
    lateinit var listenerConfig: ListenerConfig

    @Inject
    override lateinit var cleanerRepository: EventScheduleRepository

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val crudRepository: WithCrudRepository<EventScheduleModel> get() = cleanerRepository

    /** Is this cleaner enabled? */
    override val enabled: Boolean by lazy { listenerConfig.enabled }

    /** Cleanup configuration */
    override val cleanerConfig: CleanupConfig by lazy { listenerConfig.cleanup }
}
