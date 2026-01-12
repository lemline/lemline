// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.postgres

import com.lemline.common.logger.logger
import com.lemline.runner.messaging.postgres.config.PgmqConnectorConfig
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.PoolOptions
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * PGMQ (PostgreSQL Message Queue) client.
 *
 * Implements a message queue on top of PostgreSQL using the PGMQ SQL-only pattern.
 * The PGMQ schema and functions are created via Flyway migrations (V800, V801).
 *
 * Features:
 * - Messages are stored in a queue table with visibility timeout
 * - Consumers read messages and mark them as invisible for a period
 * - Messages are deleted on acknowledgment or moved to DLQ on rejection
 * - Supports message headers for metadata
 *
 * Queue tables (created by pgmq.create):
 * - pgmq.q_{queue_name}: Main queue table
 * - pgmq.a_{queue_name}: Archive table for processed messages
 *
 * @see <a href="https://github.com/pgmq/pgmq">PGMQ</a>
 */
class PgmqClient(
    private val config: PgmqConnectorConfig,
) {
    companion object {
        private val logger = logger()
        private val pools = ConcurrentHashMap<String, PgPool>()

        // PGMQ SQL statements (using functions from V801 migration)
        private const val CREATE_QUEUE = $$"SELECT pgmq.create($1)"

        private const val SEND_MESSAGE = $$"SELECT * FROM pgmq.send($1, $2::jsonb)"

        private const val SEND_MESSAGE_WITH_DELAY = $$"SELECT * FROM pgmq.send($1, $2::jsonb, $3)"

        private const val SEND_MESSAGE_WITH_HEADERS = $$"SELECT * FROM pgmq.send($1, $2::jsonb, $3::jsonb)"

        private const val SEND_MESSAGE_WITH_HEADERS_AND_DELAY =
            $$"SELECT * FROM pgmq.send($1, $2::jsonb, $3::jsonb, $4)"

        private const val READ_MESSAGES = $$"SELECT * FROM pgmq.read($1, $2, $3)"

        private const val DELETE_MESSAGE = $$"SELECT pgmq.delete($1, $2)"

        private const val ARCHIVE_MESSAGE = $$"SELECT pgmq.archive($1, $2)"

        private const val SET_VT = $$"SELECT * FROM pgmq.set_vt($1, $2, $3)"

        private const val POP_MESSAGE = $$"SELECT * FROM pgmq.pop($1, $2)"

        private const val PURGE_QUEUE = $$"SELECT pgmq.purge_queue($1)"

        private const val DROP_QUEUE = $$"SELECT pgmq.drop_queue($1)"

        private const val LIST_QUEUES = "SELECT * FROM pgmq.list_queues()"

        private const val GET_METRICS = $$"SELECT * FROM pgmq.metrics($1)"
    }

    private val poolKey = "${config.host}:${config.port}/${config.database}"

    private val pool: PgPool by lazy {
        pools.computeIfAbsent(poolKey) {
            val connectOptions = PgConnectOptions()
                .setHost(config.host)
                .setPort(config.port)
                .setDatabase(config.database)
                .setUser(config.username)
                .setPassword(config.password)

            val poolOptions = PoolOptions()
                .setMaxSize(config.maxPoolSize)

            logger.info { "Creating PGMQ connection pool for $poolKey" }
            PgPool.pool(connectOptions, poolOptions)
        }
    }

    /**
     * Initializes the queue if it doesn't exist.
     *
     * Note: The PGMQ schema and functions are created via Flyway migrations.
     * This method only creates the queue tables using pgmq.create().
     */
    suspend fun initialize() {
        logger.info { "Initializing PGMQ for queue: ${config.queue}" }

        // Create queue if auto-create is enabled
        if (config.autoCreateQueue) {
            createQueue(config.queue)
            config.deadLetterQueue?.let { dlq ->
                createQueue(dlq)
            }
        }
    }

    /**
     * Creates a PGMQ queue using pgmq.create() function.
     */
    private suspend fun createQueue(queueName: String) {
        try {
            pool.preparedQuery(CREATE_QUEUE)
                .execute(Tuple.of(queueName))
                .awaitSuspending()
            logger.info { "Created PGMQ queue: $queueName" }
        } catch (e: Exception) {
            // Queue might already exist, which is fine
            if (!e.message.orEmpty().contains("already exists")) {
                throw e
            }
            logger.debug { "Queue $queueName already exists" }
        }
    }

    /**
     * Sends a message to the queue.
     *
     * @param message The message payload as a JSON string
     * @param delaySeconds Optional delay before the message becomes visible
     * @param headers Optional message headers as a JSON string
     * @return The message ID
     */
    suspend fun send(message: String, delaySeconds: Int = 0, headers: String? = null): Long {
        val result = when {
            headers != null && delaySeconds > 0 -> {
                pool.preparedQuery(SEND_MESSAGE_WITH_HEADERS_AND_DELAY)
                    .execute(Tuple.of(config.queue, message, headers, delaySeconds))
                    .awaitSuspending()
            }

            headers != null -> {
                pool.preparedQuery(SEND_MESSAGE_WITH_HEADERS)
                    .execute(Tuple.of(config.queue, message, headers))
                    .awaitSuspending()
            }

            delaySeconds > 0 -> {
                pool.preparedQuery(SEND_MESSAGE_WITH_DELAY)
                    .execute(Tuple.of(config.queue, message, delaySeconds))
                    .awaitSuspending()
            }

            else -> {
                pool.preparedQuery(SEND_MESSAGE)
                    .execute(Tuple.of(config.queue, message))
                    .awaitSuspending()
            }
        }

        val msgId = result.iterator().next().getLong(0)
        logger.debug { "Sent message $msgId to queue ${config.queue}" }
        return msgId
    }

    /**
     * Reads messages from the queue.
     *
     * Messages are marked as invisible for the visibility timeout period.
     *
     * @param batchSize Maximum number of messages to read
     * @return List of PGMQ messages
     */
    suspend fun read(batchSize: Int = config.batchSize): List<PgmqMessage> {
        val result = pool.preparedQuery(READ_MESSAGES)
            .execute(Tuple.of(config.queue, config.visibilityTimeout, batchSize))
            .awaitSuspending()

        return result.map { row -> row.toPgmqMessage() }
    }

    /**
     * Reads messages reactively using Mutiny.
     */
    fun readReactive(batchSize: Int = config.batchSize): Uni<List<PgmqMessage>> {
        return pool.preparedQuery(READ_MESSAGES)
            .execute(Tuple.of(config.queue, config.visibilityTimeout, batchSize))
            .map { rowSet -> rowSet.map { it.toPgmqMessage() } }
    }

    /**
     * Pops messages from the queue (read and delete in one operation).
     *
     * @param batchSize Maximum number of messages to pop
     * @return List of PGMQ messages
     */
    suspend fun pop(batchSize: Int = 1): List<PgmqMessage> {
        val result = pool.preparedQuery(POP_MESSAGE)
            .execute(Tuple.of(config.queue, batchSize))
            .awaitSuspending()

        return result.map { row -> row.toPgmqMessage() }
    }

    /**
     * Deletes a message from the queue (acknowledges it).
     */
    suspend fun delete(msgId: Long): Boolean {
        val result = pool.preparedQuery(DELETE_MESSAGE)
            .execute(Tuple.of(config.queue, msgId))
            .awaitSuspending()

        val deleted = result.iterator().next().getBoolean(0)
        if (deleted) {
            logger.debug { "Deleted message $msgId from queue ${config.queue}" }
        }
        return deleted
    }

    /**
     * Deletes a message reactively.
     */
    fun deleteReactive(msgId: Long): Uni<Boolean> {
        return pool.preparedQuery(DELETE_MESSAGE)
            .execute(Tuple.of(config.queue, msgId))
            .map { it.iterator().next().getBoolean(0) }
    }

    /**
     * Archives a message (moves it to the archive table).
     */
    suspend fun archive(msgId: Long): Boolean {
        val result = pool.preparedQuery(ARCHIVE_MESSAGE)
            .execute(Tuple.of(config.queue, msgId))
            .awaitSuspending()

        val archived = result.iterator().next().getBoolean(0)
        if (archived) {
            logger.debug { "Archived message $msgId from queue ${config.queue}" }
        }
        return archived
    }

    /**
     * Archives a message reactively.
     */
    fun archiveReactive(msgId: Long): Uni<Boolean> {
        return pool.preparedQuery(ARCHIVE_MESSAGE)
            .execute(Tuple.of(config.queue, msgId))
            .map { it.iterator().next().getBoolean(0) }
    }

    /**
     * Extends the visibility timeout for a message.
     */
    suspend fun setVisibilityTimeout(msgId: Long, vtSeconds: Int): PgmqMessage? {
        val result = pool.preparedQuery(SET_VT)
            .execute(Tuple.of(config.queue, msgId, vtSeconds))
            .awaitSuspending()

        return if (result.rowCount() > 0) {
            result.iterator().next().toPgmqMessage()
        } else {
            null
        }
    }

    /**
     * Moves a message to the dead-letter queue.
     */
    suspend fun moveToDeadLetterQueue(msgId: Long, message: String, error: String) {
        val dlq = config.deadLetterQueue ?: return

        // Create a DLQ message with error info
        val dlqMessage = Json.encodeToString(
            DlqMessage.serializer(),
            DlqMessage(
                originalMessageId = msgId,
                originalQueue = config.queue,
                payload = message,
                error = error,
                timestamp = Instant.now().toString()
            )
        )

        // Send to DLQ and delete from original queue
        pool.preparedQuery(SEND_MESSAGE)
            .execute(Tuple.of(dlq, dlqMessage))
            .awaitSuspending()

        delete(msgId)
        logger.warn { "Moved message $msgId to DLQ: $dlq" }
    }

    /**
     * Purges all messages from the queue.
     *
     * @return Number of messages purged
     */
    suspend fun purge(): Long {
        val result = pool.preparedQuery(PURGE_QUEUE)
            .execute(Tuple.of(config.queue))
            .awaitSuspending()

        val purged = result.iterator().next().getLong(0)
        logger.info { "Purged $purged messages from queue ${config.queue}" }
        return purged
    }

    /**
     * Drops the queue and its archive table.
     */
    suspend fun drop(): Boolean {
        val result = pool.preparedQuery(DROP_QUEUE)
            .execute(Tuple.of(config.queue))
            .awaitSuspending()

        val dropped = result.iterator().next().getBoolean(0)
        if (dropped) {
            logger.info { "Dropped queue ${config.queue}" }
        }
        return dropped
    }

    /**
     * Closes the connection pool.
     */
    fun close() {
        pools.remove(poolKey)?.close()
    }

    private fun Row.toPgmqMessage(): PgmqMessage = PgmqMessage(
        msgId = getLong("msg_id"),
        readCt = getInteger("read_ct"),
        enqueuedAt = getLocalDateTime("enqueued_at")?.let { Instant.from(it.atOffset(java.time.ZoneOffset.UTC)) }
            ?: Instant.now(),
        vt = getLocalDateTime("vt")?.let { Instant.from(it.atOffset(java.time.ZoneOffset.UTC)) }
            ?: Instant.now(),
        message = getString("message") ?: getJsonObject("message")?.encode() ?: "",
        headers = getString("headers") ?: getJsonObject("headers")?.encode()
    )
}

/**
 * Represents a message from PGMQ.
 */
data class PgmqMessage(
    /** Unique message identifier */
    val msgId: Long,
    /** Number of times this message has been read */
    val readCt: Int,
    /** When the message was enqueued */
    val enqueuedAt: Instant,
    /** Visibility timeout - message becomes visible again after this time */
    val vt: Instant,
    /** The message payload */
    val message: String,
    /** Optional message headers */
    val headers: String? = null,
)

/**
 * Message format for dead-letter queue.
 */
@Serializable
data class DlqMessage(
    val originalMessageId: Long,
    val originalQueue: String,
    val payload: String,
    val error: String,
    val timestamp: String,
)
