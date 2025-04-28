// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.OutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.outbox.OutBoxStatus.PENDING
import com.lemline.runner.outbox.OutBoxStatus.SENT
import jakarta.transaction.Transactional
import java.sql.ResultSet
import java.time.Instant

/**
 * Base interface for outbox pattern repositories.
 * This interface defines the common operations for managing messages in the outbox pattern,
 * which is used to ensure reliable message delivery in distributed systems.
 *
 * Key features:
 * - Parallel processing safety using SKIP LOCKED
 * - Ordered processing based on timestamps
 * - Batch processing with configurable limits
 * - Automatic cleanup of processed messages
 *
 * Native SQL Queries:
 * This interface uses native SQL queries because Hibernate does not support the SKIP LOCKED feature.
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
 * The interface uses SKIP LOCKED in native SQL queries to ensure safe parallel processing:
 * 1. Multiple processors can run simultaneously without blocking each other
 * 2. Each processor gets a unique set of messages to process
 * 3. No message is processed by more than one processor at a time
 * 4. Failed locks are skipped, allowing other processors to continue
 * 5. Processing order is maintained within each batch
 *
 * @see OutboxModel for the base message model
 * @see OutboxProcessor for the processing logic
 */
abstract class OutboxRepository<T : OutboxModel> : Repository<T>() {

    override val columns: String = "id, message, status, delayed_until, attempt_count, last_error"
    override val values: String = "?, ?, ?, ?, ?, ?"

    /**
     * Creates a model instance from a ResultSet.
     * Maps the database columns to the outbox model properties.
     *
     * @param rs The ResultSet containing the current row
     * @return A new outbox model instance populated with data from the ResultSet
     */
    override fun createModel(rs: ResultSet): T = createModel(
        id = rs.getString("id"),
        message = rs.getString("message"),
        status = OutBoxStatus.valueOf(rs.getString("status")),
        delayedUntil = rs.getTimestamp("delayed_until").toInstant(),
        attemptCount = rs.getInt("attempt_count"),
        lastError = rs.getString("last_error"),
    )

    /**
     * Creates a new instance of the message model with the specified properties.
     *
     * @param id The unique identifier of the message
     * @param message The message content
     * @param status The current status of the message
     * @param delayedUntil The timestamp when the message should be processed
     * @param attemptCount The number of processing attempts made
     * @param lastError The last error message, if any
     * @return A new instance of the message model
     */
    abstract fun createModel(
        id: String,
        message: String,
        status: OutBoxStatus,
        delayedUntil: Instant,
        attemptCount: Int,
        lastError: String?,
    ): T

    /**
     * Persists a message to the database.
     * This method is transactional and uses native SQL for optimal performance.
     *
     * @param message The message to persist
     */
    @Transactional
    override fun persist(message: T) {
        val sql = getUpsertSql()

        withConnection {
            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, message.id)
                stmt.setString(2, message.message)
                stmt.setString(3, message.status.name)
                stmt.setTimestamp(4, java.sql.Timestamp.from(message.delayedUntil))
                stmt.setInt(5, message.attemptCount)
                stmt.setString(6, message.lastError)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Persists multiple messages to the database in a single batch operation.
     * This method uses database-specific batch operations for optimal performance.
     *
     * @param messages The list of messages to persist
     */
    @Transactional
    override fun persist(messages: List<T>) {
        if (messages.isEmpty()) return

        val sql = getUpsertSql()

        withConnection {
            it.prepareStatement(sql).use { stmt ->
                for (message in messages) {
                    stmt.setString(1, message.id)
                    stmt.setString(2, message.message)
                    stmt.setString(3, message.status.name)
                    stmt.setTimestamp(4, java.sql.Timestamp.from(message.delayedUntil))
                    stmt.setInt(5, message.attemptCount)
                    stmt.setString(6, message.lastError)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    /**
     * Finds and locks messages that are ready to be processed.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * @param limit Maximum number of messages to retrieve
     * @param maxAttempts Maximum number of retry attempts allowed
     * @return List of locked messages ready for processing
     */
    @Transactional
    fun findMessagesToProcess(limit: Int, maxAttempts: Int): List<T> {
        val sql = """
            SELECT * FROM $tableName
            WHERE status = ?
            AND delayed_until <= ?
            AND attempt_count < ?
            ORDER BY delayed_until ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

        return withConnection {
            it.prepareStatement(sql).use { stmt ->
                stmt.apply {
                    setString(1, PENDING.name)
                    setObject(2, Instant.now())
                    setInt(3, maxAttempts)
                    setInt(4, limit)
                }

                stmt.executeQuery().use { it.toModels() }
            }
        }
    }

    /**
     * Finds and locks messages that are ready to be deleted.
     * This method uses a native SQL query with SKIP LOCKED because Hibernate does not support this feature.
     *
     * @param cutoffDate Messages older than this date will be selected
     * @param limit Maximum number of messages to retrieve
     * @return List of locked messages ready for deletion
     */
    @Transactional
    fun findMessagesToDelete(cutoffDate: Instant, limit: Int): List<T> {
        val sql = """
            SELECT * FROM $tableName
            WHERE status = ?
            AND delayed_until <= ?
            ORDER BY delayed_until ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

        return withConnection {
            it.prepareStatement(sql).use { stmt ->
                stmt.apply {
                    setString(1, SENT.name)
                    setObject(2, cutoffDate)
                    setInt(3, limit)
                }

                stmt.executeQuery().use { it.toModels() }
            }
        }
    }

    private fun ResultSet.toModels(): List<T> = buildList {
        while (next()) {
            add(
                createModel(
                    id = getString("id"),
                    message = getString("message"),
                    status = OutBoxStatus.valueOf(getString("status")),
                    attemptCount = getInt("attempt_count"),
                    lastError = getString("last_error"),
                    delayedUntil = getTimestamp("delayed_until").toInstant(),
                )
            )
        }
    }
}
