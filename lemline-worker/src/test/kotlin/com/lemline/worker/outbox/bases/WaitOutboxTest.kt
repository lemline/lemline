// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox.bases

import com.lemline.worker.models.WaitModel
import com.lemline.worker.outbox.OutboxProcessor
import com.lemline.worker.outbox.WaitOutbox
import com.lemline.worker.repositories.WaitRepository
import jakarta.inject.Inject

/**
 * Base test class for WaitOutbox implementations that works with both MySQL and PostgreSQL.
 */
internal abstract class WaitOutboxTest : AbstractOutboxTest<WaitModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override val entity = WaitModel::class.java

    @Inject
    lateinit var outbox: WaitOutbox

    override val processor: OutboxProcessor<out WaitModel>
        get() = outbox.outboxProcessor

    override fun processOutbox() {
        outbox.processOutbox()
    }

    override fun cleanupOutbox() {
        outbox.cleanupOutbox()
    }
}
