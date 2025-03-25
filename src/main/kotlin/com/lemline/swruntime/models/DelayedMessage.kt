package com.lemline.swruntime.models

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "delayed_messages",
    // Combined index for our main query pattern: status + delayed_until + attempt_count
    indexes = [Index(name = "idx_delayed_ready", columnList = "status, delayed_until, attempt_count")]
)
class DelayedMessage : PanacheEntity() {

    @Column(nullable = false)
    lateinit var message: String

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: MessageStatus = MessageStatus.PENDING

    @Column(name = "delayed_until", nullable = false)
    lateinit var delayedUntil: Instant

    @Column(name = "attempt_count")
    var attemptCount: Int = 0

    @Column(name = "last_error", length = 1000)
    var lastError: String? = null

    @Version
    @Column(name = "version")
    var version: Long = 0

    enum class MessageStatus {
        PENDING,
        SENT,
        FAILED
    }
} 