// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

import com.lemline.runner.common.cleaner.AbstractCleaner
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Deletes published or failed creation event outbox rows once their retention has elapsed. */
@Startup
@ApplicationScoped
class CreationEventCleaner : AbstractCleaner<CreationEventModel>() {
    override val runWhenScheduledDisabled: Boolean get() = true

    @Inject
    lateinit var creationEventOutboxConfig: CreationEventOutboxConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var creationEventRepository: CreationEventRepository

    override val cleanerRepository: WithCleanerRepository<CreationEventModel>
        get() = creationEventRepository

    override val crudRepository: WithCrudRepository<CreationEventModel>
        get() = creationEventRepository

    override val enabled by lazy { creationEventOutboxConfig.enabled }

    override val cleanerConfig: CleanupConfig by lazy { creationEventOutboxConfig.cleanup }

    override val jobName: String get() = "Creation events outbox cleaner"
}
