// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

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
import kotlin.time.Clock
import kotlinx.coroutines.launch

/**
 * Relays persisted lifecycle events ([CreationEventModel]) to the lifecycle events channel.
 *
 * A row is marked completed only after the broker acknowledged it; otherwise it is retried with
 * exponential backoff and, after the configured maximum attempts, marked failed (never silently
 * treated as delivered). Consumers deduplicate by CloudEvent ID, so a publication retried after a
 * lost acknowledgment is harmless.
 *
 * It runs in every process with a database, including those where scheduled jobs are disabled,
 * so events written by a gateway or a CLI are relayed even when no worker runs.
 */
@Startup
@ApplicationScoped
class CreationEventOutbox : AbstractOutbox<CreationEventModel>() {

    override val jobName: String get() = "Creation events outbox"

    override val runWhenScheduledDisabled: Boolean get() = true

    @Inject
    public override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var creationEventOutboxConfig: CreationEventOutboxConfig

    @Inject
    public override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var creationEventRepository: CreationEventRepository

    @Inject
    lateinit var publisher: LifecycleEventPublisher

    override val outboxRepository: WithOutboxRepository<CreationEventModel>
        get() = creationEventRepository

    override val crudRepository: WithCrudRepository<CreationEventModel>
        get() = creationEventRepository

    // Read from configuration only: touching the publisher here would create the messaging
    // emitter at startup, before reactive messaging has wired its channels.
    override val enabled by lazy { creationEventOutboxConfig.enabled }

    override val outboxConfig: OutboxConfig by lazy { creationEventOutboxConfig.outbox }

    private val immediateRelays = Semaphore(4)

    override suspend fun process(entity: CreationEventModel) {
        publisher.publish(entity.eventPayload)
        // Published rows are deleted after the retention period; failed rows stay for inspection.
        entity.cleanupAfter = Clock.System.now()
    }

    /**
     * Tries to publish freshly committed rows at once, in the background. Failures are left to
     * the scheduled relay.
     */
    fun processNow(ids: Collection<IDV7>) {
        if (ids.isEmpty() || !enabled || isShuttingDown.get() || !immediateRelays.tryAcquire()) return
        scope.launch {
            try {
                relayNow(ids)
            } finally {
                immediateRelays.release()
            }
        }
    }

    /**
     * Publishes freshly committed rows and waits for the outcome (for short-lived processes such
     * as the CLI). Returns the number of rows published; failures are recorded on the rows and
     * left to the scheduled relay of any running process.
     */
    suspend fun relayNow(ids: Collection<IDV7>): Int {
        if (ids.isEmpty() || !publisher.enabled) return 0
        return try {
            databaseConfig.withTransaction { conn ->
                val rows = creationEventRepository.findReadyByIds(ids, outboxConfig.maxAttempts, conn)
                if (rows.isEmpty()) return@withTransaction 0
                val published = processEntitiesWith(rows, outboxConfig.maxAttempts, outboxConfig.retryDelay) { process(it) }
                creationEventRepository.update(rows, conn)
                published
            }
        } catch (e: Exception) {
            logger.warn(e) { "Immediate lifecycle event relay failed; scheduled relay will retry" }
            0
        }
    }
}
