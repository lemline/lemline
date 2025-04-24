// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.worker.repositories.UuidV7Entity
import java.time.Instant

abstract class OutboxModel : UuidV7Entity() {
    abstract var message: String
    abstract var status: OutBoxStatus
    abstract var attemptCount: Int
    abstract var lastError: String?
    abstract var delayedUntil: Instant
}
