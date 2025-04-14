package com.lemline.worker.repositories

import com.lemline.worker.models.TIMEOUT_TABLE
import com.lemline.worker.models.TimeoutModel
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class TimeoutRepository : UuidV7Repository<TimeoutModel>, OutboxRepository<TimeoutModel> {

    @Transactional
    fun save(retry: TimeoutModel) {
        retry.persist()
    }

    override fun delete(entity: TimeoutModel) {
        super.delete(entity)
    }

    override fun count(query: String, vararg params: Any?): Long = getEntityManager()
        .createQuery("SELECT COUNT(r) FROM TimeoutModel r WHERE $query", Long::class.java)
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
                SELECT * FROM $TIMEOUT_TABLE 
                WHERE status = ?1 
                AND delayed_until <= ?2 
                AND attempt_count < ?3 
                ORDER BY delayed_until ASC 
                FOR UPDATE SKIP LOCKED 
                LIMIT ?4
            """.trimIndent(), TimeoutModel::class.java
        )
        .setParameter(1, OutBoxStatus.PENDING.name)
        .setParameter(2, Instant.now())
        .setParameter(3, maxAttempts)
        .setParameter(4, limit)
        .resultList as List<TimeoutModel>

    @Suppress("UNCHECKED_CAST")
    override fun findAndLockForDeletion(cutoffDate: Instant, limit: Int) = getEntityManager()
        .createNativeQuery(
            """
                SELECT * FROM $TIMEOUT_TABLE 
                WHERE status = ?1 
                AND delayed_until < ?2 
                ORDER BY delayed_until ASC 
                FOR UPDATE SKIP LOCKED 
                LIMIT ?3
            """.trimIndent(), TimeoutModel::class.java
        )
        .setParameter(1, OutBoxStatus.SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<TimeoutModel>
}
