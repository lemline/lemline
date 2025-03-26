package com.lemline.swruntime.repositories

import com.lemline.swruntime.models.RETRY_TABLE
import com.lemline.swruntime.models.RetryMessage
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class RetryMessageRepository : PanacheRepository<RetryMessage> {

    @Transactional
    fun RetryMessage.save() {
        persist()
    }

    @Suppress("UNCHECKED_CAST")
    fun findAndLockReadyToProcess(limit: Int, maxAttempts: Int) = getEntityManager()
        .createNativeQuery(
            """
                SELECT * FROM $RETRY_TABLE 
                WHERE status = ?1 
                AND delayed_until <= ?2 
                AND attempt_count < ?3 
                ORDER BY delayed_until ASC 
                FOR UPDATE SKIP LOCKED 
                LIMIT ?4
            """.trimIndent(), RetryMessage::class.java
        )
        .setParameter(1, RetryMessage.MessageStatus.PENDING.name)
        .setParameter(2, Instant.now())
        .setParameter(3, maxAttempts)
        .setParameter(4, limit)
        .resultList as List<RetryMessage>

    @Suppress("UNCHECKED_CAST")
    fun findAndLockForDeletion(cutoffDate: Instant, limit: Int) = getEntityManager()
        .createNativeQuery(
            """
                SELECT * FROM $RETRY_TABLE 
                WHERE status = ?1 
                AND delayed_until < ?2 
                ORDER BY delayed_until ASC 
                FOR UPDATE SKIP LOCKED 
                LIMIT ?3
            """.trimIndent(), RetryMessage::class.java
        )
        .setParameter(1, RetryMessage.MessageStatus.SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<RetryMessage>
}
