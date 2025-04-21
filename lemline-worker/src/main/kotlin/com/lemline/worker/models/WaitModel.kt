// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.models

import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.OutboxMessage
import com.lemline.worker.repositories.UuidV7Entity
import jakarta.persistence.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

const val WAIT_TABLE = "waits"

@Entity
@Table(
    name = WAIT_TABLE,
    // Combined index for our main query pattern: status + delayed_until + attempt_count
    indexes = [Index(name = "idx_wait_ready", columnList = "status, delayed_until, attempt_count")],
)
@Serializable
class WaitModel :
    UuidV7Entity(),
    OutboxMessage {

    @Column(nullable = false, columnDefinition = "TEXT")
    override lateinit var message: String

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    override var status: OutBoxStatus = OutBoxStatus.PENDING

    @Column(name = "delayed_until", nullable = false)
    override lateinit var delayedUntil: @Contextual Instant

    @Column(name = "attempt_count", nullable = false)
    override var attemptCount: Int = 0

    @Column(name = "last_error", columnDefinition = "TEXT")
    override var lastError: String? = null

    @Version
    @Column(name = "version")
    var version: Long = 0

    companion object {
        fun create(
            message: String,
            delayedUntil: Instant = Instant.now(),
            attemptCount: Int = 0,
            lastError: Exception? = null,
            status: OutBoxStatus = OutBoxStatus.PENDING,
        ) = WaitModel().apply {
            this.message = message
            this.delayedUntil = delayedUntil
            this.attemptCount = attemptCount
            this.lastError = lastError?.message
            this.status = status
        }
    }
}
