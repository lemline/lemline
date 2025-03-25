package com.lemline.swruntime.repositories

import com.lemline.swruntime.models.DelayedMessage
import com.lemline.swruntime.models.DelayedMessage.MessageStatus
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class DelayedMessageRepository : PanacheRepository<DelayedMessage> {
    @Suppress("UNCHECKED_CAST")
    fun findAndLockReadyToProcess(limit: Int, maxAttempts: Int) = getEntityManager()
        .createNativeQuery(
            """
                SELECT * FROM delayed_messages 
                WHERE status = ?1 
                AND delayed_until <= ?2 
                AND attempt_count < ?3 
                ORDER BY delayed_until ASC 
                FOR UPDATE SKIP LOCKED 
                LIMIT ?4
            """.trimIndent(), DelayedMessage::class.java
        )
        .setParameter(1, MessageStatus.PENDING.name)
        .setParameter(2, Instant.now())
        .setParameter(3, maxAttempts)
        .setParameter(4, limit)
        .resultList as List<DelayedMessage>

    @Suppress("UNCHECKED_CAST")
    fun findAndLockForDeletion(cutoffDate: Instant, limit: Int) = getEntityManager()
        .createNativeQuery(
            """
                SELECT * FROM delayed_messages 
                WHERE status = ?1 
                AND delayed_until < ?2 
                ORDER BY delayed_until ASC 
                FOR UPDATE SKIP LOCKED 
                LIMIT ?3
            """.trimIndent(), DelayedMessage::class.java
        )
        .setParameter(1, MessageStatus.SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<DelayedMessage>
}
