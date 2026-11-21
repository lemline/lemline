// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.sqlite

import com.lemline.common.values.IDV7
import com.lemline.runner.messaging.sqlite.connector.SqliteIncomingMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
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

internal class SqliteQueue(private val dataSource: DataSource, private val file: Path? = null) {
    private class Pooled(val connection: Connection, val fileIdentity: Any)

    // Opening a SQLite connection reopens the file and reloads its schema. Queue
    // operations are frequent and short, so keep a few connections between them.
    private val idle = ArrayBlockingQueue<Pooled>(MAX_IDLE_CONNECTIONS)

    // A connection outlives the removal or replacement of its file. Reuse it only
    // while [file] is still the file it opened, so a lost queue fails loudly.
    private fun fileIdentity(): Any? = file?.let {
        try {
            val attributes = Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            attributes.fileKey() ?: attributes.creationTime()
        } catch (_: IOException) {
            null
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        val identity = fileIdentity()
        var connection: Connection? = null
        while (connection == null) {
            val pooled = idle.poll() ?: break
            if (identity != null && pooled.fileIdentity == identity) connection = pooled.connection
            else runCatching { pooled.connection.close() }
        }
        val active = connection ?: dataSource.connection
        val result = try {
            block(active).also { if (!active.autoCommit) active.autoCommit = true }
        } catch (e: Throwable) {
            // Closing rolls back whatever the failed operation left open.
            runCatching { active.close() }
            throw e
        }
        if (identity == null || !idle.offer(Pooled(active, identity))) runCatching { active.close() }
        return result
    }

    fun closeIdle() {
        while (true) {
            val pooled = idle.poll() ?: break
            runCatching { pooled.connection.close() }
        }
    }

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

    /** Returns false when an active message with the same ID is already queued: nothing is added. */
    suspend fun publish(queue: String, messageId: String, payload: ByteArray, delaySeconds: Int): Boolean =
        withContext(Dispatchers.IO) {
            require(delaySeconds >= 0) { "delaySeconds must be non-negative" }
            withConnection { connection ->
                // Auto-commit completes this statement before the producer can acknowledge.
                connection.prepareStatement(PUBLISH).use { statement ->
                    statement.setString(1, queue)
                    statement.setString(2, messageId)
                    statement.setBytes(3, payload)
                    statement.setLong(4, delaySeconds * 1_000L)
                    statement.executeUpdate() == 1
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
        withConnection { connection ->
            // Empty polls can run concurrently and never acquire SQLite's writer lock.
            val ready = connection.prepareStatement("SELECT id FROM lemline_messages AS candidate WHERE $readyPredicate LIMIT 1")
                .use { statement ->
                    statement.setString(1, queue)
                    statement.executeQuery().use { it.next() }
                }
            if (!ready) return@withConnection emptyList()

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
            withConnection { connection ->
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
        withConnection { connection ->
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
        private const val MAX_IDLE_CONNECTIONS = 8
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
