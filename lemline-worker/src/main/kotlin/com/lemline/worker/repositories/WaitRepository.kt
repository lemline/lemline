// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.models.WAIT_TABLE
import com.lemline.worker.models.WaitModel
import com.lemline.worker.outbox.OutBoxStatus
import com.lemline.worker.outbox.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Repository for managing wait messages in the outbox pattern.
 * This repository handles the persistence and retrieval of wait messages,
 * which are used to implement delayed execution in workflows.
 *
 * Key features:
 * - Parallel processing safety using SKIP LOCKED
 * - Ordered processing based on delay timestamps
 * - Batch processing with configurable limits
 * - Automatic cleanup of processed messages
 *
 * Native SQL Queries:
 * This repository uses native SQL queries because Hibernate does not support the SKIP LOCKED feature.
 * While Hibernate provides other locking mechanisms, SKIP LOCKED is essential for our parallel processing
 * requirements as it allows multiple processors to work on different messages simultaneously without blocking.
 *
 * Database Support:
 * The SKIP LOCKED feature is supported by:
 * - PostgreSQL 9.5+
 * - Oracle 10g+
 * - MySQL 8.0+ (with InnoDB)
 * - MariaDB 10.3+ (with InnoDB)
 * - IBM DB2 9.7+
 * 
 * Note: SQL Server uses a different syntax (UPDLOCK, READPAST) and is not supported
 *
 * Parallel Processing Safety:
 * The repository uses SKIP LOCKED in native SQL queries to ensure safe parallel processing:
 * 1. Multiple processors can run simultaneously without blocking each other
 * 2. Each processor gets a unique set of messages to process
 * 3. No message is processed by more than one processor at a time
 * 4. Failed locks are skipped, allowing other processors to continue
 * 5. Processing order is maintained within each batch
 *
 * The repository uses native SQL queries for:
 * 1. Finding and locking messages ready for processing
 * 2. Finding and locking messages ready for cleanup
 *
 * @see OutboxRepository for the base repository interface
 * @see WaitModel for the message model
 * @see OutboxProcessor for the processing logic
 */
@ApplicationScoped
class WaitRepository : OutboxRepository<WaitModel> {

    /**
     * Saves a wait message to the database.
     * This method is transactional and uses Panache's persist() method
     * to ensure the message is properly stored.
     *
     * @param wait The wait message to save
     */
    @Transactional
    fun save(wait: WaitModel) {
        wait.persist()
    }

    @Inject
    private lateinit var entityManager: EntityManager

    /**
     * Finds and locks messages that are ready to be processed.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * Native SQL Usage:
     * - Uses native SQL as Hibernate does not support SKIP LOCKED
     * - Essential for parallel processing without blocking
     * - Ensures consistent behavior across supported databases
     *
     * Parallel Processing Guarantees:
     * - Multiple processors can run this query simultaneously
     * - Each processor gets a unique set of messages
     * - No message is processed by more than one processor
     * - Failed locks are skipped, allowing other processors to continue
     * - Processing order is maintained within each batch
     *
     * The query selects messages that:
     * 1. Have PENDING status
     * 2. Have reached their scheduled processing time
     * 3. Haven't exceeded maximum retry attempts
     * 4. Are ordered by their scheduled time
     *
     * @param limit Maximum number of messages to retrieve
     * @param maxAttempts Maximum number of retry attempts allowed
     * @return List of locked messages ready for processing
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    override fun findAndLockReadyToProcess(limit: Int, maxAttempts: Int) = entityManager
        .createNativeQuery(
            """
            SELECT * FROM $WAIT_TABLE
            WHERE status = ?1
            AND delayed_until <= ?2
            AND attempt_count < ?3
            ORDER BY delayed_until ASC
            LIMIT ?4
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            WaitModel::class.java,
        )
        .setParameter(1, OutBoxStatus.PENDING.name)
        .setParameter(2, Instant.now())
        .setParameter(3, maxAttempts)
        .setParameter(4, limit)
        .resultList as List<WaitModel>

    /**
     * Finds and locks messages that are ready to be deleted.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * Native SQL Usage:
     * - Uses native SQL as Hibernate does not support SKIP LOCKED
     * - Essential for parallel processing without blocking
     * - Ensures consistent behavior across supported databases
     *
     * Parallel Processing Guarantees:
     * - Multiple cleanup processes can run simultaneously
     * - Each process gets a unique set of messages to delete
     * - No message is deleted by more than one process
     * - Failed locks are skipped, allowing other processes to continue
     * - Cleanup order is maintained within each batch
     *
     * The query selects messages that:
     * 1. Have SENT status
     * 2. Are older than the cutoff date
     * 3. Are ordered by their scheduled time
     *
     * @param cutoffDate Messages older than this date will be selected
     * @param limit Maximum number of messages to retrieve
     * @return List of locked messages ready for deletion
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    override fun findAndLockReadyToDelete(cutoffDate: Instant, limit: Int) = entityManager
        .createNativeQuery(
            """
            SELECT * FROM $WAIT_TABLE
            WHERE status = ?1
            AND delayed_until < ?2
            ORDER BY delayed_until ASC
            LIMIT ?3
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            WaitModel::class.java,
        )
        .setParameter(1, OutBoxStatus.SENT.name)
        .setParameter(2, cutoffDate)
        .setParameter(3, limit)
        .resultList as List<WaitModel>
}
