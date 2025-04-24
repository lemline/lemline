// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.common.logger
import com.lemline.worker.config.LemlineConfiguration
import com.lemline.worker.config.WaitConfig
import com.lemline.worker.repositories.WaitRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
internal class WaitOutbox @Inject constructor(
    repository: WaitRepository,
    lemlineConfig: LemlineConfiguration,

    @Channel("workflows-out")
    val emitter: Emitter<String>,
) {
    private val logger = logger()

    private val waitConfig: WaitConfig = lemlineConfig.wait()

    internal val outboxProcessor = OutboxProcessor(
        logger = logger,
        repository = repository,
        processor = { waitMessage -> emitter.send(waitMessage.message) },
    )

    /**
     * Wait outbox processing
     * Every {}, this method is called
     * - select messages from the outbox table that are not yet sent
     * - send them with the emitter
     */
    @Scheduled(every = "{lemline.wait.outbox.every}", concurrentExecution = SKIP)
    fun processOutbox() {
        val outboxConf = waitConfig.outbox()
        outboxProcessor.process(
            outboxConf.batchSize(),
            outboxConf.maxAttempts(),
            outboxConf.initialDelay().toSeconds().toInt(),
        )
    }

    /**
     * Wait outbox cleanup
     * Every {}, this method is called
     * - select messages from the outbox table that are sent
     * - delete them
     */
    @Scheduled(every = "{lemline.wait.cleanup.every}", concurrentExecution = SKIP)
    fun cleanupOutbox() {
        val cleanupConf = waitConfig.cleanup()
        outboxProcessor.cleanup(
            cleanupConf.after().toDays().toInt(),
            cleanupConf.batchSize(),
        )
    }
}
