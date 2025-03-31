package com.lemline.swruntime.repositories

import com.lemline.swruntime.models.WAIT_TABLE
import com.lemline.swruntime.models.WaitMessage
import com.lemline.swruntime.outbox.OutBoxStatus
import com.lemline.swruntime.outbox.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
internal class WaitRepository : UuidV7Repository<WaitMessage>, OutboxRepository<WaitMessage> {

    @Transactional
    fun WaitMessage.save() {
        persist()
    }

    override fun delete(entity: WaitMessage) {
        super.delete(entity)
    }

    override fun count(query: String, vararg params: Any?): Long = getEntityManager()
        .createQuery("SELECT COUNT(w) FROM WaitMessage w WHERE $query", Long::class.java)
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
                SELECT * FROM $WAIT_TABLE 
                WHERE status = ?1 
                AND delayed_until <= ?2 
                AND attempt_count < ?3 
                ORDER BY delayed_until ASC 
                FOR UPDATE SKIP LOCKED 
                LIMIT ?4
            """.trimIndent(), WaitMessage::class.java
        )
        .setParameter(1, OutBoxStatus.PENDING.name)
        .setParameter(2, Instant.now())
        .setParameter(3, maxAttempts)
        .setParameter(4, limit)
        .resultList as List<WaitMessage>

    @Suppress("UNCHECKED_CAST")
    override fun findAndLockForDeletion(cutoffDate: Instant, limit: Int) = getEntityManager()
        .createNativeQuery(
            """
                SELECT * FROM $WAIT_TABLE 
                WHERE status = ?1 
                AND delayed_until < ?2 
                ORDER BY delayed_until ASC 
                FOR UPDATE SKIP LOCKED 
                LIMIT ?3
            """.trimIndent(), WaitMessage::class.java
        )
        .setParameter(1, OutBoxStatus.SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<WaitMessage>
}
