// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.sqlite

import com.lemline.common.values.IDV7
import com.lemline.runner.messaging.sqlite.connector.SqliteIncomingMetadata
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class SqliteDelivery(
    val rowId: Long,
    val leaseId: String,
    val payload: ByteArray,
    val metadata: SqliteIncomingMetadata,
)

internal class SqliteQueue(private val dataSource: DataSource) {
    fun verifySchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    """
                    SELECT id, queue, message_id, payload, enqueued_at, available_at, attempts, lease_id, is_dead_letter
                    FROM lemline_messages LIMIT 0
                    """.trimIndent()
                )
                val primaryKey = it.executeQuery("PRAGMA table_info(lemline_messages)").use { columns ->
                    buildList {
                        while (columns.next()) {
                            if (columns.getInt("pk") > 0) add(columns.getString("name") to columns.getString("type"))
                        }
                    }
                }
                check(primaryKey == listOf("id" to "INTEGER")) {
                    "The SQLite queue requires an integer primary key for message IDs"
                }
            }
            // Preparing the publication verifies that its conflict target has a matching unique index.
            connection.prepareStatement("EXPLAIN $PUBLISH").close()
        }
    }

    suspend fun publish(queue: String, messageId: String, payload: ByteArray, delaySeconds: Int): Unit =
        withContext(Dispatchers.IO) {
            require(delaySeconds >= 0) { "delaySeconds must be non-negative" }
            dataSource.connection.use { connection ->
                // Auto-commit completes this statement before the producer can acknowledge.
                connection.prepareStatement(PUBLISH).use { statement ->
                    statement.setString(1, queue)
                    statement.setString(2, messageId)
                    statement.setBytes(3, payload)
                    statement.setLong(4, delaySeconds * 1_000L)
                    statement.executeUpdate()
                }
            }
        }

    suspend fun reserve(
        queue: String,
        batchSize: Int,
        visibilitySeconds: Int,
        ordered: Boolean = false,
    ): List<SqliteDelivery> = withContext(Dispatchers.IO) {
        val readyPredicate = if (ordered) "$READY AND $QUEUE_HEAD" else READY
        val limit = if (ordered) 1 else batchSize
        dataSource.connection.use { connection ->
            // Empty polls can run concurrently and never acquire SQLite's writer lock.
            val ready = connection.prepareStatement("SELECT id FROM lemline_messages AS candidate WHERE $readyPredicate LIMIT 1")
                .use { statement ->
                    statement.setString(1, queue)
                    statement.executeQuery().use { it.next() }
                }
            if (!ready) return@use emptyList()

            val lease = IDV7.random().toString()
            // Recheck eligibility inside the write statement. Another process may have
            // reserved the rows since the read above. Read the clock inside the statement
            // so time spent waiting for the write lock doesn't shorten the lease.
            connection.autoCommit = false
            val deliveries = connection.prepareStatement(
                """
                UPDATE lemline_messages
                SET lease_id = ?, available_at = $NOW + ?
                WHERE id IN (
                    SELECT id FROM lemline_messages AS candidate
                    WHERE $readyPredicate ORDER BY available_at, id LIMIT ?
                )
                RETURNING id, queue, message_id, payload, enqueued_at, available_at, attempts
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, lease)
                statement.setLong(2, visibilitySeconds * 1_000L)
                statement.setString(3, queue)
                statement.setInt(4, limit)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.delivery(lease)) }
                }
            }
            connection.commit()
            deliveries.sortedBy { it.rowId }
        }
    }

    // A reservation expires even if the worker crashes before delivery. Once the
    // subscriber can receive it, charge an attempt and give the handler its full lease.
    suspend fun beginDelivery(delivery: SqliteDelivery, visibilitySeconds: Int): SqliteIncomingMetadata? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                val started = connection.prepareStatement(
                    """
                    UPDATE lemline_messages
                    SET attempts = attempts + 1, available_at = $NOW + ?
                    WHERE id = ? AND lease_id = ? AND available_at > $NOW
                    RETURNING attempts, available_at
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, visibilitySeconds * 1_000L)
                    statement.setLong(2, delivery.rowId)
                    statement.setString(3, delivery.leaseId)
                    statement.executeQuery().use { row ->
                        if (row.next()) delivery.metadata.copy(
                            readCount = row.getInt("attempts"),
                            visibilityTimeout = Instant.ofEpochMilli(row.getLong("available_at")),
                        ) else null
                    }
                }
                connection.commit()
                started
            }
        }

    suspend fun ack(delivery: SqliteDelivery): Boolean = settle(delivery, "DELETE FROM lemline_messages")

    // As with PGMQ, keep the message invisible until the end of the visibility timeout.
    // Clear the lease so a second callback for this delivery cannot settle it again.
    // An ordered queue cannot pass its head, so that wait would stall every later
    // message: [immediately] makes the failed head eligible for its next attempt now.
    suspend fun release(delivery: SqliteDelivery, immediately: Boolean = false): Boolean = settle(
        delivery,
        "UPDATE lemline_messages SET lease_id = NULL" + if (immediately) ", available_at = $NOW" else "",
    )

    suspend fun deadLetter(delivery: SqliteDelivery, target: String, reason: String): Boolean {
        val payload = buildJsonObject {
            put("originalMessageId", delivery.rowId)
            put("originalQueue", delivery.metadata.queue)
            put("messageId", delivery.metadata.messageId)
            try {
                put("payload", delivery.payload.decodeToString(throwOnInvalidSequence = true))
            } catch (_: java.nio.charset.CharacterCodingException) {
                put("payloadBase64", Base64.getEncoder().encodeToString(delivery.payload))
            }
            put("error", reason)
            put("timestamp", Instant.now().toString())
        }.toString().encodeToByteArray()
        // Transfer the active copy and release its deduplication key in one write. A
        // later send of the same logical ID can fail independently and keep its own record.
        return settle(
            delivery,
            """
            UPDATE lemline_messages
            SET queue = ?, payload = ?, available_at = $NOW, attempts = 0, lease_id = NULL, is_dead_letter = 1
            """.trimIndent(),
            listOf(target, payload),
        )
    }

    private suspend fun settle(
        delivery: SqliteDelivery,
        mutation: String,
        parameters: List<Any> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("$mutation WHERE id = ? AND lease_id = ?").use { statement ->
                parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.setLong(parameters.size + 1, delivery.rowId)
                statement.setString(parameters.size + 2, delivery.leaseId)
                statement.executeUpdate() == 1
            }
        }
    }

    private fun ResultSet.delivery(lease: String) = SqliteDelivery(
        rowId = getLong("id"),
        leaseId = lease,
        payload = getBytes("payload"),
        metadata = SqliteIncomingMetadata(
            messageId = getString("message_id"),
            queue = getString("queue"),
            readCount = getInt("attempts"),
            enqueuedAt = Instant.ofEpochMilli(getLong("enqueued_at")),
            visibilityTimeout = Instant.ofEpochMilli(getLong("available_at")),
        ),
    )

    companion object {
        private const val NOW = "CAST(unixepoch('subsec') * 1000 AS INTEGER)"
        private const val READY = "candidate.queue = ? AND candidate.available_at <= $NOW"
        // An ordered queue cannot pass its head while it is delayed or leased.
        private const val QUEUE_HEAD =
            "candidate.id = (SELECT id FROM lemline_messages WHERE queue = candidate.queue ORDER BY id LIMIT 1)"
        private val PUBLISH = """
            INSERT INTO lemline_messages (queue, message_id, payload, enqueued_at, available_at, attempts, lease_id, is_dead_letter)
            VALUES (?, ?, ?, $NOW, $NOW + ?, 0, NULL, 0)
            ON CONFLICT (queue, message_id) WHERE is_dead_letter = 0 DO NOTHING
        """.trimIndent()
    }
}
