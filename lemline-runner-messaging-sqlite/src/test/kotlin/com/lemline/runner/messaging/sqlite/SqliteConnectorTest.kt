// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.sqlite

import com.lemline.runner.messaging.sqlite.connector.SqliteConnector
import com.lemline.runner.messaging.sqlite.connector.SqliteIncomingMetadata
import com.lemline.runner.messaging.sqlite.connector.SqliteOutgoingMetadata
import io.smallrye.config.SmallRyeConfigBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class SqliteConnectorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `local paths and independent connections keep the SQLite settings`() {
        val path = directory.resolve("messages #%é.db")
        for (invalid in listOf("", " ", ":memory:", "file:test?mode=memory", "jdbc:sqlite:foo", directory.toString())) {
            assertFailsWith<IllegalArgumentException> { SqliteDatabase.resolve(invalid) }
        }
        SqliteDatabase.initialize(path)
        assertTrue(Files.isRegularFile(path))
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
        }
        repeat(2) {
            SqliteDatabase.open(path).connection.use { connection ->
                connection.createStatement().use { statement ->
                    fun pragma(key: String) = statement.executeQuery("PRAGMA $key").use {
                        assertTrue(it.next())
                        it.getString(1)
                    }
                    assertEquals("wal", pragma("journal_mode"))
                    assertEquals("1", pragma("foreign_keys"))
                    assertEquals("2", pragma("synchronous"))
                    assertEquals("5000", pragma("busy_timeout"))
                }
            }
        }
    }

    @Test
    fun `paths resolve aliases before and after the queue file exists`() {
        val path = directory.resolve("messages.db")
        val alias = runCatching { Files.createSymbolicLink(directory.resolve("alias"), directory) }
        assumeTrue(alias.isSuccess, "Symlinks unavailable: ${alias.exceptionOrNull()}")
        assertEquals(directory.toRealPath().resolve(path.fileName), SqliteDatabase.resolve(alias.getOrThrow().resolve(path.fileName).toString()))
        SqliteDatabase.initialize(path)
        val fileAlias = Files.createSymbolicLink(directory.resolve("file-alias"), path)
        assertEquals(path.toRealPath(), SqliteDatabase.resolve(fileAlias.toString()))
    }

    @Test
    fun `startup rejects queue schemas that cannot publish reserve or deduplicate`() {
        val schemaChanges = listOf(
            listOf("ALTER TABLE lemline_messages DROP COLUMN attempts"),
            listOf("DROP INDEX lemline_messages_active_id"),
            listOf(
                "CREATE TABLE wrong_schema AS SELECT * FROM lemline_messages",
                "DROP TABLE lemline_messages",
                "ALTER TABLE wrong_schema RENAME TO lemline_messages",
                "CREATE UNIQUE INDEX lemline_messages_active_id ON lemline_messages (queue, message_id) WHERE is_dead_letter = 0",
            ),
        )
        for ((index, changes) in schemaChanges.withIndex()) {
            val path = directory.resolve("messages-$index.db")
            SqliteDatabase.initialize(path)
            SqliteDatabase.open(path).connection.use { connection ->
                connection.createStatement().use { statement -> changes.forEach { statement.execute(it) } }
            }
            assertFailsWith<IllegalStateException> { SqliteDatabase.initialize(path) }
        }
    }

    @Test
    fun `a path expression that resolves to blank fails connector startup`() {
        val config = SmallRyeConfigBuilder().addDefaultInterceptors().withDefaultValues(
            mapOf("channel-name" to "test", "path" to "${'$'}{missing.lemline.queue.path:}")
        ).build()
        val connector = SqliteConnector()
        try {
            assertEquals("", config.getConfigValue("path").value)
            assertFailsWith<IllegalArgumentException> { connector.getSubscriber(config) }
        } finally {
            connector.destroy()
        }
    }

    @Test
    fun `a dead-letter queue cannot also be consumed by the same input channel`() {
        val path = directory.resolve("messaging.db")
        val connector = SqliteConnector()
        try {
            assertFailsWith<IllegalArgumentException> {
                connector.getPublisher(channelConfig(path, "queues" to "work,work.dlq"))
            }
            assertFalse(Files.exists(path))
        } finally {
            connector.destroy()
        }
    }

    @Test
    fun `startup rejects hard-link aliases that would split the WAL`() {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("unix"))
        val path = directory.resolve("messaging.db")
        SqliteDatabase.initialize(path)
        val alias = Files.createLink(directory.resolve("alias.db"), path)
        assertFailsWith<IllegalStateException> { SqliteDatabase.initialize(alias) }
        assertFailsWith<IllegalStateException> { SqliteDatabase.initialize(path) }
    }

    @Test
    fun `startup rejects a linked WAL sidecar before JDBC can open it`() {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("unix"))
        val path = directory.resolve("messaging.db")
        SqliteDatabase.initialize(path)
        val other = Files.createFile(directory.resolve("other.db-wal"))
        Files.createLink(directory.resolve("messaging.db-wal"), other)
        assertFailsWith<IllegalStateException> { SqliteDatabase.initialize(path) }
    }

    @Test
    fun `startup rejects a non-file migration lock before opening the queue`() {
        val path = directory.resolve("messaging.db")
        Files.createDirectory(directory.resolve(".messaging.db.lemline-migrate.lock"))
        assertFailsWith<IllegalStateException> { SqliteDatabase.initialize(path) }
        assertFalse(Files.exists(path))
    }

    @Test
    fun `JDBC cannot recreate a missing queue file`() = runBlocking {
        val path = directory.resolve("messaging.db")
        val queue = SqliteDatabase.initialize(path)
        Files.delete(path)
        assertFailsWith<SQLException> { queue.publish("work", "id", "payload".encodeToByteArray(), 0) }
        assertFalse(Files.exists(path))
    }

    @Test
    fun `delivery renews the lease before handing the message to the subscriber`() = runBlocking {
        val path = directory.resolve("messaging.db")
        val queue = SqliteDatabase.initialize(path)
        queue.publish("work", "id", "payload".encodeToByteArray(), 0)
        val prefetched = queue.reserve("work", 1, 5).single()
        delay(100)
        val delivered = assertNotNull(queue.beginDelivery(prefetched, 5))
        assertEquals(1, delivered.readCount)
        assertTrue(delivered.visibilityTimeout >= prefetched.metadata.visibilityTimeout.plusMillis(75))
        assertTrue(queue.ack(prefetched))
    }

    @Test
    fun `round robin serves a sparse queue while another is busy and quarantines unreadable rows`() = runBlocking {
        val path = directory.resolve("messaging.db")
        val connector = SqliteConnector()
        val outgoing = connector.producer(path)
        try {
            publish(outgoing, "hot", "bad-bytes", byteArrayOf(0xC3.toByte(), 0x28))
            repeat(25) { publish(outgoing, "hot", "hot-$it", "payload-$it") }
            // The same ID is independent in each queue.
            publish(outgoing, "cold", "hot-0", """{"payload":"été"}""")

            val received = connector.consume(path, "queues" to "hot, cold", "batch-size" to "3")
            val first = received.take()
            val second = received.take()
            val third = received.take()
            assertEquals(listOf("hot", "hot", "cold"), listOf(first, second, third).map { it.sqlite.queue })
            assertEquals(listOf("hot-0", "hot-1", "hot-0"), listOf(first, second, third).map { it.sqlite.messageId })
            assertEquals("""{"payload":"été"}""", third.payload)
            listOf(first, second, third).forEach { it.ack().await() }

            val failed = connector.consume(path, "queue" to "hot.dlq").take()
            assertEquals("bad-bytes", failed.sqlite.messageId)
            assertTrue(failed.payload.toString().contains("\"payloadBase64\":\"wyg=\""))
            assertTrue(failed.payload.toString().contains("Unreadable message"))
            assertEquals("hot-2", received.take().sqlite.messageId)
        } finally {
            connector.destroy()
        }
    }

    @Test
    fun `ordered CloudEvents wait for the head across connections while another queue progresses`() = runBlocking {
        val path = directory.resolve("messaging.db")
        val publisher = SqliteDatabase.initialize(path)
        val peer = SqliteQueue(SqliteDatabase.open(path))
        publisher.publish("first-namespace", "reading", "reading".encodeToByteArray(), 0)
        publisher.publish("first-namespace", "stop", "stop".encodeToByteArray(), 0)
        publisher.publish("second-namespace", "unrelated", "unrelated".encodeToByteArray(), 0)

        val first = publisher.reserve("first-namespace", 10, 30, ordered = true).single()
        assertEquals("reading", first.metadata.messageId)
        assertTrue(peer.reserve("first-namespace", 10, 30, ordered = true).isEmpty())
        val sibling = peer.reserve("second-namespace", 10, 30, ordered = true).single()
        assertEquals("unrelated", sibling.metadata.messageId)
        assertTrue(peer.ack(sibling))

        assertTrue(publisher.ack(first))
        val next = peer.reserve("first-namespace", 10, 30, ordered = true).single()
        assertEquals("stop", next.metadata.messageId)
        assertTrue(peer.ack(next))
    }

    @Test
    fun `expired prefetch with zero demand leaves the undelivered row's retry budget intact`(): Unit = runBlocking {
        val path = directory.resolve("messaging.db")
        val connector = SqliteConnector()
        try {
            val outgoing = connector.producer(path)
            publish(outgoing, "work", "first", "first")
            publish(outgoing, "work", "second", "second")
            val options = arrayOf("queue" to "work", "visibility-timeout" to "1", "max-retries" to "1")
            val first = connector.consume(path, *options, demand = 1).take()
            assertEquals("first", first.sqlite.messageId)
            first.ack().await()
            withTimeout(4_000) { while (Instant.now() < first.sqlite.visibilityTimeout) delay(10) }
            val second = connector.consume(path, *options).take()
            assertEquals("second", second.sqlite.messageId)
            assertEquals(1, second.sqlite.readCount)
            second.ack().await()
        } finally {
            connector.destroy()
        }
    }

    @Test
    fun `delayed messages and negative acknowledgments follow visibility and attempt limits`() = runBlocking {
        val path = directory.resolve("messaging.db")
        val connector = SqliteConnector()
        try {
            val outgoing = connector.producer(path)
            publish(outgoing, "ready", "first", "delayed", delaySeconds = 1)
            publish(outgoing, "ready", "second", "immediate")
            val incoming = connector.consume(path, "queue" to "ready", "visibility-timeout" to "1",
                "max-retries" to "2", "dead-letter-queue" to "failed")
            val immediate = incoming.take()
            assertEquals("immediate", immediate.payload)
            immediate.ack().await()
            val delayed = incoming.take()
            assertEquals("delayed", delayed.payload)
            assertEquals(1, delayed.sqlite.readCount)
            assertTrue(Instant.now() >= delayed.sqlite.enqueuedAt.plusSeconds(1))
            delayed.nack(IllegalStateException("retry")).await()
            val retried = incoming.take()
            assertEquals(delayed.sqlite.messageId, retried.sqlite.messageId)
            assertEquals(2, retried.sqlite.readCount)
            assertTrue(Instant.now() >= delayed.sqlite.visibilityTimeout)
            retried.nack(IllegalStateException("permanent")).await()

            val failed = connector.consume(path, "queue" to "failed").take()
            assertEquals("first", failed.sqlite.messageId)
            assertTrue(failed.payload.toString().contains("\"error\":\"permanent\""))
            failed.ack().await()
            assertFalse(SqliteQueue(SqliteDatabase.open(path)).reserve("ready", 1, 1).isNotEmpty())
        } finally {
            connector.destroy()
        }
    }

    @Test
    fun `an ordered queue retries its failed head at once so later messages are not stalled`(): Unit = runBlocking {
        val path = directory.resolve("messaging.db")
        val connector = SqliteConnector()
        try {
            val outgoing = connector.producer(path)
            publish(outgoing, "events", "poison", "poison")
            publish(outgoing, "events", "next", "next")
            // A long lease would block "next" for a minute if the failed head waited it out.
            val incoming = connector.consume(path, "queue" to "events", "ordered" to "true",
                "visibility-timeout" to "30", "max-retries" to "2")
            val started = Instant.now()
            incoming.take().nack(IllegalStateException("retry")).await()
            val retried = incoming.take()
            assertEquals("poison", retried.sqlite.messageId)
            assertEquals(2, retried.sqlite.readCount)
            retried.nack(IllegalStateException("permanent")).await()
            val next = incoming.take()
            assertEquals("next", next.sqlite.messageId)
            assertTrue(Instant.now() < started.plusSeconds(8))
            next.ack().await()
        } finally {
            connector.destroy()
        }
    }

    @Test
    fun `a handler finishing after shutdown can still confirm and publish`() = runBlocking {
        val path = directory.resolve("messaging.db")
        val connector = SqliteConnector()
        val outgoing = connector.producer(path)
        publish(outgoing, "work", "id", "payload")
        val inFlight = connector.consume(path, "queue" to "work").take()
        connector.destroy()

        publish(outgoing, "results", "result", "done")
        inFlight.ack().await()
        val queue = SqliteQueue(SqliteDatabase.open(path))
        assertTrue(queue.reserve("results", 1, 1).isNotEmpty())
        SqliteDatabase.open(path).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM lemline_messages WHERE queue = 'work'").use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
            }
        }
    }
}

internal fun channelConfig(path: Path, vararg options: Pair<String, String>) =
    SmallRyeConfigBuilder().withDefaultValues(
        mapOf("path" to path.toString(), "channel-name" to "test", "poll-interval" to "20") + options
    ).build()

@Suppress("UNCHECKED_CAST")
internal fun SqliteConnector.producer(path: Path): Flow.Subscriber<Message<*>> =
    (getSubscriber(channelConfig(path)) as Flow.Subscriber<Message<*>>).also { outgoing ->
        outgoing.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) = Unit
            override fun cancel() = Unit
        })
    }

internal suspend fun publish(
    outgoing: Flow.Subscriber<Message<*>>,
    queue: String,
    messageId: String,
    payload: Any,
    delaySeconds: Int = 0,
) {
    val ack = CompletableFuture<Void>()
    outgoing.onNext(Message.of(payload)
        .addMetadata(SqliteOutgoingMetadata(messageId, queue, delaySeconds))
        .withAck { ack.complete(null); ack }
        .withNack { ack.completeExceptionally(it); ack })
    withTimeout(8_000) { ack.await() }
}

internal fun SqliteConnector.consume(
    path: Path,
    vararg options: Pair<String, String>,
    demand: Long = Long.MAX_VALUE,
): Channel<Message<*>> {
    val delivered = Channel<Message<*>>(Channel.UNLIMITED)
    getPublisher(channelConfig(path, *options)).subscribe(object : Flow.Subscriber<Message<*>> {
        override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(demand)
        override fun onNext(item: Message<*>) { check(delivered.trySend(item).isSuccess) }
        override fun onError(error: Throwable) { delivered.close(error) }
        override fun onComplete() { delivered.close() }
    })
    return delivered
}

internal suspend fun Channel<Message<*>>.take() = withTimeout(8_000) { receive() }

internal val Message<*>.sqlite: SqliteIncomingMetadata
    get() = getMetadata(SqliteIncomingMetadata::class.java).orElseThrow()
