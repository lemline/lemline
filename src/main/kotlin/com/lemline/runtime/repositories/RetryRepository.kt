package com.lemline.runtime.repositories

import com.lemline.runtime.models.RETRY_TABLE
import com.lemline.runtime.models.RetryMessage
import com.lemline.runtime.outbox.OutBoxStatus
import com.lemline.runtime.outbox.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
internal class RetryRepository : UuidV7Repository<RetryMessage>, OutboxRepository<RetryMessage> {

    @Transactional
    fun RetryMessage.save() {
        persist()
    }

    override fun delete(entity: RetryMessage) {
        super.delete(entity)
    }

    override fun count(query: String, vararg params: Any?): Long = getEntityManager()
        .createQuery("SELECT COUNT(r) FROM RetryMessage r WHERE $query", Long::class.java)
        .apply {
            params.forEachIndexed { index, param ->
                setParameter(index + 1, param)
            }
        }
        .singleResult

    @Suppress("UNCHECKED_CAST")
    override fun findAndLockReadyToProcess(limit: Int, maxAttempts: Int) = getEntityManager()
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
        .setParameter(1, OutBoxStatus.PENDING.name)
        .setParameter(2, Instant.now())
        .setParameter(3, maxAttempts)
        .setParameter(4, limit)
        .resultList as List<RetryMessage>

    @Suppress("UNCHECKED_CAST")
    override fun findAndLockForDeletion(cutoffDate: Instant, limit: Int) = getEntityManager()
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
        .setParameter(1, OutBoxStatus.SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<RetryMessage>
}
