package com.lemline.worker.outbox

import com.lemline.common.logger
import com.lemline.worker.repositories.TimeoutRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
internal class TimeoutOutbox(
    repository: TimeoutRepository,

    @Channel("workflows-out")
    val emitter: Emitter<String>,

    @ConfigProperty(name = "timeout.outbox.batch-size", defaultValue = "100")
    val batchSize: Int,

    @ConfigProperty(name = "timeout.outbox.max-attempts", defaultValue = "5")
    val retryMaxAttempts: Int,

    @ConfigProperty(name = "timeout.outbox.initial-delay-seconds", defaultValue = "10")
    val retryInitialDelaySeconds: Int,

    @ConfigProperty(name = "timeout.cleanup.after-days", defaultValue = "7")
    val cleanupAfterDays: Int,

    @ConfigProperty(name = "timeout.cleanup.batch-size", defaultValue = "500")
    val cleanupBatchSize: Int
) {
    private val logger = logger()

//    private val outboxProcessor = OutboxProcessor(
//        logger = logger,
//        repository = repository,
//        processor = { retryMessage -> emitter.send(retryMessage.message) },
//    )

    @Scheduled(every = "{timeout.outbox.schedule}", concurrentExecution = SKIP)
    fun processOutbox() {
        //outboxProcessor.process(batchSize, retryMaxAttempts, retryInitialDelaySeconds)
    }

    @Scheduled(every = "{timeout.cleanup.schedule}", concurrentExecution = SKIP)
    fun cleanupOutbox() {
        //outboxProcessor.cleanup(cleanupAfterDays, cleanupBatchSize)
    }
}