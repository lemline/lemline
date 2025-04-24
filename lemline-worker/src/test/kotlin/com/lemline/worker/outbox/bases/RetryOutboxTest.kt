// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox.bases

import com.lemline.worker.models.RetryModel
import com.lemline.worker.outbox.OutboxProcessor
import com.lemline.worker.outbox.RetryOutbox
import com.lemline.worker.repositories.RetryRepository
import jakarta.inject.Inject

/**
 * Base test class for RetryOutbox implementations that works with both MySQL and PostgreSQL.
 */
internal abstract class RetryOutboxTest : AbstractOutboxTest<RetryModel>() {

    @Inject
    override lateinit var repository: RetryRepository

    override val entity = RetryModel::class.java

    @Inject
    lateinit var outbox: RetryOutbox

    override val processor: OutboxProcessor<out RetryModel>
        get() = outbox.outboxProcessor

    override fun processOutbox() {
        outbox.processOutbox()
    }

    override fun cleanupOutbox() {
        outbox.cleanupOutbox()
    }
}
