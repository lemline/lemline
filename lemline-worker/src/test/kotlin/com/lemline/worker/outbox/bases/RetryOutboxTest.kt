// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox.bases

import com.lemline.worker.models.RetryModel
import com.lemline.worker.outbox.RetryOutbox
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
            cleanupAfter = java.time.Duration.ofDays(7),
            initialDelay = java.time.Duration.ofSeconds(5),
        )
    }

    override val processor by lazy {
        outbox.outboxProcessor
    }

    override fun processOutbox() {
        outbox.processRetryOutbox()
    }

    override fun cleanupOutbox() {
        outbox.cleanupOutbox()
    }
}
