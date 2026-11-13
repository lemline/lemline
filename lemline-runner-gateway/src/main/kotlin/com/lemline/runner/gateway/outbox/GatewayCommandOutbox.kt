// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.common.values.IDV7
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.Semaphore
import kotlinx.coroutines.launch

/**
 * Outbox processor for gateway workflow start commands.
 *
 * Reliably delivers workflow start commands that were inserted atomically
 * alongside schedule inserts during [com.lemline.runner.gateway.instances.InstanceStartService.start].
 * The outbox pattern ensures commands are eventually delivered even if the process
 * crashes after the database transaction commits.
 */
@Startup
@ApplicationScoped
class GatewayCommandOutbox : AbstractOutbox<GatewayCommandModel>() {

    override val jobName: String get() = "Gateway command outbox"

    override val runWhenScheduledDisabled: Boolean get() = true

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var gatewayCommandConfig: GatewayCommandConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var gatewayCommandRepository: GatewayCommandRepository

    override val outboxRepository: WithOutboxRepository<GatewayCommandModel>
        get() = gatewayCommandRepository

    override val crudRepository: WithCrudRepository<GatewayCommandModel>
        get() = gatewayCommandRepository

    override val enabled by lazy { gatewayCommandConfig.enabled }

    override val outboxConfig: OutboxConfig by lazy { gatewayCommandConfig.outbox }

    private val immediateRelays = Semaphore(4)

    override suspend fun process(entity: GatewayCommandModel) {
        commandEmitter.send(entity.instanceMessage, entity.id)
    }

    /** Try the new command immediately after its insert commits; the scheduled relay remains the recovery path. */
    suspend fun processNow(id: IDV7) {
        if (!enabled || isShuttingDown.get() || !immediateRelays.tryAcquire()) return

        scope.launch {
            try {
                databaseConfig.withTransaction { conn ->
                    val entity = gatewayCommandRepository.findReadyById(id, outboxConfig.maxAttempts, conn)
                        ?: return@withTransaction
                    processEntitiesWith(listOf(entity), outboxConfig.maxAttempts, outboxConfig.retryDelay) { process(it) }
                    gatewayCommandRepository.update(entity, conn)
                }
            } catch (e: Exception) {
                logger.warn(e) { "Immediate gateway command relay failed; scheduled relay will retry" }
            } finally {
                immediateRelays.release()
            }
        }
    }
}
