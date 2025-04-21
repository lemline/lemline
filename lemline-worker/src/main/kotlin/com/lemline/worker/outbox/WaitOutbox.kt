// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.common.logger
import com.lemline.worker.repositories.WaitRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
internal class WaitOutbox(
    repository: WaitRepository,

    @Channel("workflows-out")
    val emitter: Emitter<String>,

    @ConfigProperty(name = "wait.outbox.batch-size", defaultValue = "100")
    val batchSize: Int,

    @ConfigProperty(name = "wait.outbox.max-attempts", defaultValue = "5")
    val retryMaxAttempts: Int,

    @ConfigProperty(name = "wait.outbox.initial-delay-seconds", defaultValue = "10")
    val retryInitialDelaySeconds: Int,

    @ConfigProperty(name = "wait.cleanup.after-days", defaultValue = "7")
    val cleanupAfterDays: Int,

    @ConfigProperty(name = "wait.cleanup.batch-size", defaultValue = "500")
    val cleanupBatchSize: Int
) {
    private val logger = logger()

    internal val outboxProcessor = OutboxProcessor(
        logger = logger,
        repository = repository,
        processor = { waitMessage -> emitter.send(waitMessage.message) },
    )

    @Scheduled(every = "{wait.outbox.schedule}", concurrentExecution = SKIP)
    fun processOutbox() {
        outboxProcessor.process(batchSize, retryMaxAttempts, retryInitialDelaySeconds)
    }

    @Scheduled(every = "{wait.cleanup.schedule}", concurrentExecution = SKIP)
    fun cleanupOutbox() {
        outboxProcessor.cleanup(cleanupAfterDays, cleanupBatchSize)
    }
}
