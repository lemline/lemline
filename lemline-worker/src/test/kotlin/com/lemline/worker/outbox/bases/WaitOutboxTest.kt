package com.lemline.worker.outbox.bases

import com.lemline.worker.models.WaitModel
import com.lemline.worker.outbox.WaitOutbox
import com.lemline.worker.repositories.WaitRepository
import jakarta.inject.Inject

/**
 * Base test class for WaitOutbox implementations that works with both MySQL and PostgreSQL.
 */
abstract class WaitOutboxTest : AbstractOutboxTest<WaitModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override val entity = WaitModel::class.java

    private val outbox by lazy {
        WaitOutbox(
            repository = repository,
            emitter = emitter,
            retryMaxAttempts = 3,
            batchSize = 100,
            cleanupBatchSize = 500,
            cleanupAfterDays = 7,
            retryInitialDelaySeconds = 5
        )
    }

    override val processor by lazy {
        outbox.outboxProcessor
    }

    override fun processOutbox() {
        outbox.processOutbox()
    }

    override fun cleanupOutbox() {
        outbox.cleanupOutbox()
    }
}
