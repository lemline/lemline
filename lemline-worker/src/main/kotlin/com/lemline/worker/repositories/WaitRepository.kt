package com.lemline.worker.repositories

import com.lemline.worker.models.WAIT_TABLE
import com.lemline.worker.models.WaitModel
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class WaitRepository : UuidV7Repository<WaitModel>, OutboxRepository<WaitModel> {

    @Transactional
    fun save(wait: WaitModel) {
        wait.persist()
    }

    override fun delete(entity: WaitModel) {
        super.delete(entity)
    }

    override fun count(query: String, vararg params: Any?): Long = getEntityManager()
        .createQuery("SELECT COUNT(w) FROM WaitModel w WHERE $query", Long::class.java)
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
            LIMIT ?4
            FOR UPDATE SKIP LOCKED
            """.trimIndent(), WaitModel::class.java
        )
        .setParameter(1, OutBoxStatus.PENDING.name)
        .setParameter(2, Instant.now())
        .setParameter(3, maxAttempts)
        .setParameter(4, limit)
        .resultList as List<WaitModel>

    @Suppress("UNCHECKED_CAST")
    override fun findAndLockForDeletion(cutoffDate: Instant, limit: Int) = getEntityManager()
        .createNativeQuery(
            """
            SELECT * FROM $WAIT_TABLE 
            WHERE status = ?1 
            AND delayed_until < ?2 
            ORDER BY delayed_until ASC 
            LIMIT ?3
            FOR UPDATE SKIP LOCKED
            """.trimIndent(), WaitModel::class.java
        )
        .setParameter(1, OutBoxStatus.SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<WaitModel>
}
