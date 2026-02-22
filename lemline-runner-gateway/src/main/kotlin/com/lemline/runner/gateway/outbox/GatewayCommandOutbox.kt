// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Outbox processor for gateway workflow start commands.
 *
 * Reliably delivers workflow start commands that were inserted atomically
 * alongside schedule inserts during [com.lemline.runner.gateway.start.WorkflowStartService.start].
 * The outbox pattern ensures commands are eventually delivered even if the process
 * crashes after the database transaction commits.
 */
@Startup
@ApplicationScoped
class GatewayCommandOutbox : AbstractOutbox<GatewayCommandOutboxModel>() {

    override val jobName: String get() = "Gateway command outbox"

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var gatewayOutboxConfig: GatewayOutboxConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var gatewayCommandOutboxRepository: GatewayCommandOutboxRepository

    override val outboxRepository: WithOutboxRepository<GatewayCommandOutboxModel>
        get() = gatewayCommandOutboxRepository

    override val crudRepository: WithCrudRepository<GatewayCommandOutboxModel>
        get() = gatewayCommandOutboxRepository

    override val enabled by lazy { gatewayOutboxConfig.enabled }

    override val outboxConfig: OutboxConfig by lazy { gatewayOutboxConfig.outbox }

    override suspend fun process(entity: GatewayCommandOutboxModel) {
        commandEmitter.send(entity.instanceMessage, entity.id)
    }
}
