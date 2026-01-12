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
 * Based on PGMQ v1.8.1.
 *
 * Features:
 * - Messages are stored in a queue table with visibility timeout
 * - Consumers read messages and mark them as invisible for a period
 * - Messages are deleted on acknowledgment or moved to DLQ on rejection
 * - Supports message headers for metadata
 * - Long polling support via [readWithPoll] (v1.8.1)
 * - Batch operations: [sendBatch], [deleteBatch], [archiveBatch], [setVisibilityTimeoutBatch] (v1.8.1)
 * - Unlogged queues via [createUnloggedQueue] for high-performance non-critical workloads (v1.8.1)
 *
 * Queue tables (created by pgmq.create):
 * - pgmq.q_{queue_name}: Main queue table
 * - pgmq.a_{queue_name}: Archive table for processed messages
 *
 * @see <a href="https://github.com/tembo-io/pgmq">PGMQ</a>
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

        private const val SEND_MESSAGE_WITH_DELAY = $$"SELECT * FROM pgmq.send($1, $2::jsonb, $3::integer)"

        private const val SEND_MESSAGE_WITH_HEADERS = $$"SELECT * FROM pgmq.send($1, $2::jsonb, $3::jsonb)"

        private const val SEND_MESSAGE_WITH_HEADERS_AND_DELAY =
            $$"SELECT * FROM pgmq.send($1, $2::jsonb, $3::jsonb, $4::integer)"

        private const val READ_MESSAGES = $$"SELECT * FROM pgmq.read($1, $2, $3)"

        private const val DELETE_MESSAGE = $$"SELECT pgmq.delete($1, $2::bigint)"

        private const val ARCHIVE_MESSAGE = $$"SELECT pgmq.archive($1, $2::bigint)"

        private const val SET_VT = $$"SELECT * FROM pgmq.set_vt($1, $2::bigint, $3::integer)"

        private const val POP_MESSAGE = $$"SELECT * FROM pgmq.pop($1, $2)"

        private const val PURGE_QUEUE = $$"SELECT pgmq.purge_queue($1)"

        private const val DROP_QUEUE = $$"SELECT pgmq.drop_queue($1)"

        private const val LIST_QUEUES = "SELECT * FROM pgmq.list_queues()"

        private const val GET_METRICS = $$"SELECT * FROM pgmq.metrics($1)"

        // Lemline-specific: Message deduplication index (see V802 migration)
        private const val CREATE_DEDUP_INDEX = $$"SELECT lemline.create_dedup_index($1)"

        // v1.8.1 additions
        private const val CREATE_UNLOGGED_QUEUE = $$"SELECT pgmq.create_unlogged($1)"

        private const val READ_WITH_POLL = $$"SELECT * FROM pgmq.read_with_poll($1, $2, $3, $4, $5)"

        private const val SEND_BATCH = $$"SELECT * FROM pgmq.send_batch($1, $2::jsonb[])"

        private const val SEND_BATCH_WITH_DELAY = $$"SELECT * FROM pgmq.send_batch($1, $2::jsonb[], $3::integer)"

        private const val DELETE_BATCH = $$"SELECT * FROM pgmq.delete($1, $2::bigint[])"

        private const val ARCHIVE_BATCH = $$"SELECT * FROM pgmq.archive($1, $2::bigint[])"

        private const val SET_VT_BATCH = $$"SELECT * FROM pgmq.set_vt($1, $2::bigint[], $3::integer)"
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
     *
     * Also creates a Lemline-specific deduplication index on headers->>'messageId'
     * to prevent duplicate messages from being enqueued.
     *
     * @param queueName Name of the queue to create
     * @param unlogged If true, creates an unlogged queue (faster but not crash-safe) (v1.8.1)
     */
    private suspend fun createQueue(queueName: String, unlogged: Boolean = false) {
        try {
            val query = if (unlogged) CREATE_UNLOGGED_QUEUE else CREATE_QUEUE
            pool.preparedQuery(query)
                .execute(Tuple.of(queueName))
                .awaitSuspending()
            val queueType = if (unlogged) "unlogged " else ""
            logger.info { "Created ${queueType}PGMQ queue: $queueName" }

            // Create Lemline-specific deduplication index (see V802 migration)
            createDedupIndex(queueName)
        } catch (e: Exception) {
            // Queue might already exist, which is fine
            if (!e.message.orEmpty().contains("already exists")) {
                throw e
            }
            logger.debug { "Queue $queueName already exists" }

            // Ensure dedup index exists even for pre-existing queues
            createDedupIndex(queueName)
        }
    }

    /**
     * Creates the Lemline-specific deduplication index for a queue.
     *
     * This index ensures that messages with the same messageId in headers
     * cannot be enqueued twice, providing send-side deduplication.
     *
     * @param queueName Name of the queue
     */
    private suspend fun createDedupIndex(queueName: String) {
        try {
            pool.preparedQuery(CREATE_DEDUP_INDEX)
                .execute(Tuple.of(queueName))
                .awaitSuspending()
            logger.debug { "Created deduplication index for queue: $queueName" }
        } catch (e: Exception) {
            // Index might already exist, which is fine
            if (!e.message.orEmpty().contains("already exists")) {
                logger.warn(e) { "Failed to create deduplication index for queue: $queueName" }
            }
        }
    }

    /**
     * Creates an unlogged PGMQ queue (v1.8.1).
     *
     * Unlogged queues are faster but not crash-safe - data may be lost on crash.
     * Use for temporary or non-critical message queues where performance is priority.
     *
     * @param queueName Name of the queue to create
     */
    suspend fun createUnloggedQueue(queueName: String) {
        createQueue(queueName, unlogged = true)
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
     * Sends multiple messages to the queue in a single batch (v1.8.1).
     *
     * More efficient than multiple individual sends as it uses a single database round-trip.
     *
     * @param messages List of message payloads as JSON strings
     * @param delaySeconds Optional delay before messages become visible
     * @return List of message IDs
     */
    suspend fun sendBatch(messages: List<String>, delaySeconds: Int = 0): List<Long> {
        if (messages.isEmpty()) return emptyList()

        val messagesArray = messages.toTypedArray()

        val result = if (delaySeconds > 0) {
            pool.preparedQuery(SEND_BATCH_WITH_DELAY)
                .execute(Tuple.of(config.queue, messagesArray, delaySeconds))
                .awaitSuspending()
        } else {
            pool.preparedQuery(SEND_BATCH)
                .execute(Tuple.of(config.queue, messagesArray))
                .awaitSuspending()
        }

        val msgIds = result.map { it.getLong(0) }
        logger.debug { "Sent ${msgIds.size} messages to queue ${config.queue}" }
        return msgIds
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
     * Reads messages with long polling (v1.8.1).
     *
     * This is more efficient than client-side polling as the database handles
     * the wait loop, reducing round-trips and returning messages as soon as
     * they become available.
     *
     * @param batchSize Maximum number of messages to read
     * @param maxPollSeconds Maximum time to wait for messages (default: 5 seconds)
     * @param pollIntervalMs Interval between poll attempts in milliseconds (default: 100ms)
     * @return List of PGMQ messages
     */
    suspend fun readWithPoll(
        batchSize: Int = config.batchSize,
        maxPollSeconds: Int = 5,
        pollIntervalMs: Int = 100,
    ): List<PgmqMessage> {
        val result = pool.preparedQuery(READ_WITH_POLL)
            .execute(
                Tuple.of(
                    config.queue,
                    config.visibilityTimeout,
                    batchSize,
                    maxPollSeconds,
                    pollIntervalMs
                )
            )
            .awaitSuspending()

        return result.map { row -> row.toPgmqMessage() }
    }

    /**
     * Reads messages with long polling reactively using Mutiny (v1.8.1).
     */
    fun readWithPollReactive(
        batchSize: Int = config.batchSize,
        maxPollSeconds: Int = 5,
        pollIntervalMs: Int = 100,
    ): Uni<List<PgmqMessage>> {
        return pool.preparedQuery(READ_WITH_POLL)
            .execute(
                Tuple.of(
                    config.queue,
                    config.visibilityTimeout,
                    batchSize,
                    maxPollSeconds,
                    pollIntervalMs
                )
            )
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
     * Deletes multiple messages from the queue in a single batch (v1.8.1).
     *
     * @param msgIds List of message IDs to delete
     * @return List of successfully deleted message IDs
     */
    suspend fun deleteBatch(msgIds: List<Long>): List<Long> {
        if (msgIds.isEmpty()) return emptyList()

        val result = pool.preparedQuery(DELETE_BATCH)
            .execute(Tuple.of(config.queue, msgIds.toLongArray()))
            .awaitSuspending()

        val deletedIds = result.map { it.getLong(0) }
        logger.debug { "Deleted ${deletedIds.size} messages from queue ${config.queue}" }
        return deletedIds
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
     * Archives multiple messages in a single batch (v1.8.1).
     *
     * @param msgIds List of message IDs to archive
     * @return List of successfully archived message IDs
     */
    suspend fun archiveBatch(msgIds: List<Long>): List<Long> {
        if (msgIds.isEmpty()) return emptyList()

        val result = pool.preparedQuery(ARCHIVE_BATCH)
            .execute(Tuple.of(config.queue, msgIds.toLongArray()))
            .awaitSuspending()

        val archivedIds = result.map { it.getLong(0) }
        logger.debug { "Archived ${archivedIds.size} messages from queue ${config.queue}" }
        return archivedIds
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
     * Extends the visibility timeout for multiple messages (v1.8.1).
     *
     * @param msgIds List of message IDs to update
     * @param vtSeconds New visibility timeout in seconds
     * @return List of updated messages
     */
    suspend fun setVisibilityTimeoutBatch(msgIds: List<Long>, vtSeconds: Int): List<PgmqMessage> {
        if (msgIds.isEmpty()) return emptyList()

        val result = pool.preparedQuery(SET_VT_BATCH)
            .execute(Tuple.of(config.queue, msgIds.toLongArray(), vtSeconds))
            .awaitSuspending()

        return result.map { it.toPgmqMessage() }
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
