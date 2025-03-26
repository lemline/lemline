package com.lemline.swruntime.repositories

import com.lemline.swruntime.models.DelayedMessage
import com.lemline.swruntime.models.DelayedMessage.MessageStatus.PENDING
import com.lemline.swruntime.models.DelayedMessage.MessageStatus.SENT
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class DelayedMessageRepository : PanacheRepository<DelayedMessage> {

    @Transactional
    fun saveMessage(message: String, delayedUntil: Instant) {
        val delayedMessage = DelayedMessage().apply {
            this.message = message
            this.delayedUntil = delayedUntil
            this.status = PENDING
        }
        delayedMessage.persist()
    }

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
        .setParameter(1, PENDING.name)
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
        .setParameter(1, SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<DelayedMessage>
}
