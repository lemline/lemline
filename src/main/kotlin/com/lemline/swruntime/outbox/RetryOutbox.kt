package com.lemline.swruntime.outbox

import com.lemline.swruntime.logger
import com.lemline.swruntime.repositories.RetryRepository
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

    @ConfigProperty(name = "retry.outbox.initial-delay-seconds", defaultValue = "10")
    val retryInitialDelaySeconds: Int,

    @ConfigProperty(name = "retry.cleanup.after-days", defaultValue = "7")
    val cleanupAfterDays: Int,

    @ConfigProperty(name = "retry.cleanup.batch-size", defaultValue = "500")
    val cleanupBatchSize: Int
) {
    private val logger = logger()

    private val outboxProcessor = OutboxProcessor(
        logger = logger,
        repository = repository,
        processor = { retryMessage -> emitter.send(retryMessage.message) },
    )

    @Scheduled(every = "{retry.outbox.schedule}", concurrentExecution = SKIP)
    fun processOutbox() {
        outboxProcessor.process(batchSize, retryMaxAttempts, retryInitialDelaySeconds)
    }

    @Scheduled(every = "{retry.cleanup.schedule}", concurrentExecution = SKIP)
    fun cleanupOutbox() {
        outboxProcessor.cleanup(cleanupAfterDays, cleanupBatchSize)
    }
}