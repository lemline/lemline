// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.common.logger
import com.lemline.worker.config.LemlineConfiguration
import com.lemline.worker.repositories.RetryRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
internal class RetryOutbox @Inject constructor(
    repository: RetryRepository,
    lemlineConfig: LemlineConfiguration,

    @Channel("workflows-out")
    val emitter: Emitter<String>,
) {
    private val logger = logger()

    private val retryConfig = lemlineConfig.retry()

    internal val outboxProcessor = OutboxProcessor(
        logger = logger,
        repository = repository,
        processor = { retryMessage -> emitter.send(retryMessage.message) },
    )

    @Scheduled(every = "{lemline.retry.outbox.every}", concurrentExecution = SKIP)
    fun processRetryOutbox() {
        val outboxConf = retryConfig.outbox()
        outboxProcessor.process(
            outboxConf.batchSize(),
            outboxConf.maxAttempts(),
            outboxConf.initialDelay().toSeconds().toInt(),
        )
    }

    @Scheduled(every = "{lemline.retry.cleanup.every}", concurrentExecution = SKIP)
    fun cleanupOutbox() {
        val cleanupConf = retryConfig.cleanup()
        outboxProcessor.cleanup(
            cleanupConf.after().toDays().toInt(),
            cleanupConf.batchSize(),
        )
    }
}
