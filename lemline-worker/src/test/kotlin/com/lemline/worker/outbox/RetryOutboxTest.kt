package com.lemline.worker.outbox

import com.lemline.worker.models.RetryModel
import com.lemline.worker.repositories.RetryRepository
import jakarta.inject.Inject

/**
 * Base test class for RetryOutbox implementations that works with both MySQL and PostgreSQL.
 */
abstract class RetryOutboxTest : AbstractOutboxTest<RetryModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override val entity = RetryModel::class.java

    private val outbox by lazy {
        RetryOutbox(
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

