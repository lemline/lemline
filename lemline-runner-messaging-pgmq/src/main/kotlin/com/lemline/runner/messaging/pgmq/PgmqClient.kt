// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.pgmq

import com.lemline.common.logger.logger
import com.lemline.runner.messaging.pgmq.config.PgmqConnectorConfig
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgBuilder
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.PoolOptions
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
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
        private val pools = ConcurrentHashMap<String, Pool>()

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

    private val pool: Pool by lazy {
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
            PgBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .build()
        }
    }

    /**
     * Initializes the queue if it doesn't exist.
     *
     * Note: The PGMQ schema and functions are created via Flyway migrations.
     * This method only creates the queue tables using pgmq.create().
     */
    suspend fun initialize() {

        // Create queue if auto-create is enabled
        if (config.autoCreateQueue) {
            logger.info { "Initializing PGMQ for queue: ${config.queue}" }
            createQueue(config.queue)
            config.deadLetterQueue?.let { dlq ->
                logger.info { "Initializing PGMQ for queue: $dlq" }
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
        val query = if (unlogged) CREATE_UNLOGGED_QUEUE else CREATE_QUEUE
        val queueType = if (unlogged) "unlogged " else ""

        runIgnoringAlreadyExists(
            action = {
                pool.preparedQuery(query)
                    .execute(Tuple.of(queueName))
                    .awaitSuspending()
                logger.info { "Created ${queueType}PGMQ queue: $queueName" }
            },
            onAlreadyExists = { logger.debug { "Queue $queueName already exists" } }
        )

        // Ensure dedup index exists (whether queue was just created or already existed)
        createDedupIndex(queueName)
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
        runIgnoringAlreadyExists(
            action = {
                pool.preparedQuery(CREATE_DEDUP_INDEX)
                    .execute(Tuple.of(queueName))
                    .awaitSuspending()
                logger.debug { "Created deduplication index for queue: $queueName" }
            },
            onError = { e -> logger.warn(e) { "Failed to create deduplication index for queue: $queueName" } }
        )
    }

    /**
     * Executes an action, ignoring "already exists" errors.
     *
     * @param action The action to execute
     * @param onAlreadyExists Optional callback when the resource already exists
     * @param onError Optional callback for other errors (if null, the error is rethrown)
     */
    private inline fun runIgnoringAlreadyExists(
        action: () -> Unit,
        onAlreadyExists: () -> Unit = {},
        noinline onError: ((Exception) -> Unit)? = null,
    ) {
        try {
            action()
        } catch (e: Exception) {
            if (e.message.orEmpty().contains("already exists")) {
                onAlreadyExists()
            } else {
                onError?.invoke(e) ?: throw e
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

        // Sort by msgId to ensure FIFO order (UPDATE...RETURNING doesn't guarantee order)
        return result.map { row -> row.toPgmqMessage() }.sortedBy { it.msgId }
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

        // Sort by msgId to ensure FIFO order (UPDATE...RETURNING doesn't guarantee order)
        return result.map { row -> row.toPgmqMessage() }.sortedBy { it.msgId }
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
     *
     * @param msgId The message ID to delete
     * @return true if the message was deleted, false if not found
     */
    suspend fun delete(msgId: Long): Boolean {
        return pool.preparedQuery(DELETE_MESSAGE)
            .execute(Tuple.of(config.queue, msgId))
            .awaitSuspending()
            .iterator().next().getBoolean(0)
            .also { deleted ->
                if (deleted) logger.debug { "Deleted message $msgId from queue ${config.queue}" }
            }
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
     *
     * @param msgId The message ID to archive
     * @return true if the message was archived, false if not found
     */
    suspend fun archive(msgId: Long): Boolean {
        return pool.preparedQuery(ARCHIVE_MESSAGE)
            .execute(Tuple.of(config.queue, msgId))
            .awaitSuspending()
            .iterator().next().getBoolean(0)
            .also { archived ->
                if (archived) logger.debug { "Archived message $msgId from queue ${config.queue}" }
            }
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
     *
     * @param msgId The message ID to update
     * @param vtSeconds New visibility timeout in seconds
     * @return The updated message, or null if not found
     */
    suspend fun setVisibilityTimeout(msgId: Long, vtSeconds: Int): PgmqMessage? {
        return pool.preparedQuery(SET_VT)
            .execute(Tuple.of(config.queue, msgId, vtSeconds))
            .awaitSuspending()
            .takeIf { it.rowCount() > 0 }
            ?.iterator()?.next()?.toPgmqMessage()
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
        return pool.preparedQuery(PURGE_QUEUE)
            .execute(Tuple.of(config.queue))
            .awaitSuspending()
            .iterator().next().getLong(0)
            .also { purged -> logger.info { "Purged $purged messages from queue ${config.queue}" } }
    }

    /**
     * Drops the queue and its archive table.
     *
     * @return true if the queue was dropped, false if not found
     */
    suspend fun drop(): Boolean {
        return pool.preparedQuery(DROP_QUEUE)
            .execute(Tuple.of(config.queue))
            .awaitSuspending()
            .iterator().next().getBoolean(0)
            .also { dropped ->
                if (dropped) logger.info { "Dropped queue ${config.queue}" }
            }
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
        enqueuedAt = getLocalDateTime("enqueued_at").toInstantUtc(),
        vt = getLocalDateTime("vt").toInstantUtc(),
        message = getString("message") ?: getJsonObject("message")?.encode() ?: "",
        headers = getString("headers") ?: getJsonObject("headers")?.encode()
    )

    private fun LocalDateTime?.toInstantUtc(): Instant =
        this?.toInstant(ZoneOffset.UTC) ?: Instant.now()
}
