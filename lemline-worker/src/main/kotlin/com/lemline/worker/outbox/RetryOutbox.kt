// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.common.logger
import com.lemline.worker.repositories.RetryRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
internal class RetryOutbox(
    repository: RetryRepository,

    @Channel("workflows-out")
    val emitter: Emitter<String>,

    @ConfigProperty(name = "retry.outbox.batch-size", defaultValue = "100")
    val batchSize: Int,

    @ConfigProperty(name = "retry.outbox.max-attempts", defaultValue = "5")
    val retryMaxAttempts: Int,

    @ConfigProperty(name = "retry.outbox.initial-delay", defaultValue = "10s")
    val initialDelay: java.time.Duration,

    @ConfigProperty(name = "retry.cleanup.after", defaultValue = "7d")
    val cleanupAfter: java.time.Duration,

    @ConfigProperty(name = "retry.cleanup.batch-size", defaultValue = "500")
    val cleanupBatchSize: Int,
) {
    private val logger = logger()

    internal val outboxProcessor = OutboxProcessor(
        logger = logger,
        repository = repository,
        processor = { retryMessage -> emitter.send(retryMessage.message) },
    )

    @Scheduled(every = "{retry.outbox.every}", concurrentExecution = SKIP)
    fun processRetryOutbox() {
        outboxProcessor.process(batchSize, retryMaxAttempts, initialDelay.toSeconds().toInt())
    }

    @Scheduled(every = "{retry.cleanup.every}", concurrentExecution = SKIP)
    fun cleanupOutbox() {
        outboxProcessor.cleanup(cleanupAfter.toDays().toInt(), cleanupBatchSize)
    }
}
