package com.lemline.swruntime.outbox

import com.lemline.swruntime.logger
import com.lemline.swruntime.repositories.RetryRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
class RetryOutbox(
    repository: RetryRepository,

    @Channel("workflows-out")
    val emitter: Emitter<String>,

    @ConfigProperty(name = "delayed.batch-size", defaultValue = "100")
    val batchSize: Int,

    @ConfigProperty(name = "delayed.retry-max-attempts", defaultValue = "5")
    val retryMaxAttempts: Int,

    @ConfigProperty(name = "delayed.retry-initial-delay-seconds", defaultValue = "10")
    val retryInitialDelaySeconds: Int,

    @ConfigProperty(name = "delayed.cleanup-after-days", defaultValue = "7")
    val cleanupAfterDays: Int,

    @ConfigProperty(name = "delayed.cleanup-batch-size", defaultValue = "500")
    val cleanupBatchSize: Int,
) {
    private val logger = logger()

    private val outboxProcessor = OutboxProcessor(logger, repository) { retryMessage ->
        emitter.send(retryMessage.message)
    }

    @Scheduled(every = "5s", concurrentExecution = SKIP)
    @Transactional
    fun processOutbox() {
        outboxProcessor.process(batchSize, retryMaxAttempts, retryInitialDelaySeconds)
    }

    @Scheduled(every = "1h", concurrentExecution = SKIP)
    @Transactional
    fun cleanupOutbox() {
        outboxProcessor.cleanup(cleanupAfterDays, cleanupBatchSize)
    }
}