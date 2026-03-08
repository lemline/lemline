// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.cleaner.AbstractCleaner
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Cleaner for gateway command outbox entities.
 * Deletes completed or failed outbox entries that have exceeded their retention period.
 */
@Startup
@ApplicationScoped
class GatewayCommandCleaner : AbstractCleaner<GatewayCommandModel>() {
    override val runWhenScheduledDisabled: Boolean get() = true

    @Inject
    lateinit var gatewayCommandConfig: GatewayCommandConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var gatewayCommandRepository: GatewayCommandRepository

    override val cleanerRepository: WithCleanerRepository<GatewayCommandModel>
        get() = gatewayCommandRepository

    override val crudRepository: WithCrudRepository<GatewayCommandModel>
        get() = gatewayCommandRepository

    override val enabled by lazy { gatewayCommandConfig.enabled }

    override val cleanerConfig: CleanupConfig by lazy { gatewayCommandConfig.cleanup }

    override val jobName: String get() = "Gateway command outbox cleaner"
}
