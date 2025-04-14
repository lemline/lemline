package com.lemline.worker.models

import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.OutboxMessage
import com.lemline.worker.repositories.UuidV7Entity
import jakarta.persistence.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.*
import kotlin.time.Duration
import kotlin.time.toJavaDuration

const val TIMEOUT_TABLE = "timeouts"

@Entity
@Table(
    name = TIMEOUT_TABLE,
    // Combined index for our main query pattern: status + delayed_until + attempt_count
    indexes = [
        Index(name = "idx_timeouts_ready", columnList = "status, delayed_until, attempt_count"),
        Index(name = "idx_timeouts_id_position", columnList = "instanceId, position")
    ]
)
@Serializable
class TimeoutModel : UuidV7Entity(), OutboxMessage {

    @Column(nullable = false)
    lateinit var instanceId: @Contextual UUID

    @Column(nullable = false)
    lateinit var position: String

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
            instanceId: UUID,
            position: String,
            duration: Duration,
            attemptCount: Int = 0,
            lastError: Exception? = null,
            status: OutBoxStatus = OutBoxStatus.PENDING
        ) = TimeoutModel().apply {
            this.instanceId = instanceId
            this.position = position
            this.delayedUntil = Instant.now().plus(duration.toJavaDuration())
            this.attemptCount = attemptCount
            this.lastError = lastError?.message
            this.status = status
        }
    }
}
