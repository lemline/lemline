// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.worker.repositories.UuidV7Repository
import java.time.Instant

internal interface OutboxRepository<T : OutboxModel> : UuidV7Repository<T> {
    fun findAndLockReadyToProcess(limit: Int, maxAttempts: Int): List<T>
    fun findAndLockForDeletion(cutoffDate: Instant, limit: Int): List<T>
}
