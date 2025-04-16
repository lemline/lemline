package com.lemline.worker.repositories

import com.lemline.worker.models.RETRY_TABLE
import com.lemline.worker.models.RetryModel
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class RetryRepository : UuidV7Repository<RetryModel>, OutboxRepository<RetryModel> {

    @Transactional
    fun save(retry: RetryModel) {
        retry.persist()
    }

    override fun delete(entity: RetryModel) {
        super.delete(entity)
    }

    override fun count(query: String, vararg params: Any?): Long = getEntityManager()
        .createQuery("SELECT COUNT(r) FROM RetryModel r WHERE $query", Long::class.java)
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
            LIMIT ?4
            FOR UPDATE SKIP LOCKED
            """.trimIndent(), RetryModel::class.java
        )
        .setParameter(1, OutBoxStatus.PENDING.name)
        .setParameter(2, Instant.now())
        .setParameter(3, maxAttempts)
        .setParameter(4, limit)
        .resultList as List<RetryModel>

    @Suppress("UNCHECKED_CAST")
    override fun findAndLockForDeletion(cutoffDate: Instant, limit: Int) = getEntityManager()
        .createNativeQuery(
            """
            SELECT * FROM $RETRY_TABLE 
            WHERE status = ?1 
            AND delayed_until < ?2 
            ORDER BY delayed_until ASC 
            LIMIT ?3
            FOR UPDATE SKIP LOCKED
            """.trimIndent(), RetryModel::class.java
        )
        .setParameter(1, OutBoxStatus.SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<RetryModel>
}
