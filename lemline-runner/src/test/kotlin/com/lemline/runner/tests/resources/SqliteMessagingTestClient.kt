// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.config.LEMLINE_MESSAGING_SQLITE_PATH
import com.lemline.runner.messaging.sqlite.connector.SqliteConnector
import com.lemline.runner.messaging.sqlite.connector.SqliteOutgoingMetadata
import io.smallrye.config.SmallRyeConfigBuilder
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message

/** External producer/consumer for the file opened by the runner. */
internal class SqliteMessagingTestClient : AutoCloseable {
    private val file = ConfigProvider.getConfig().getValue(LEMLINE_MESSAGING_SQLITE_PATH, String::class.java)
    private val connector = SqliteConnector()

    @Suppress("UNCHECKED_CAST")
    private val producer = (connector.getSubscriber(config("test-producer")) as Flow.Subscriber<Message<*>>).also {
        it.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) = Unit
            override fun cancel() = Unit
        })
    }

    suspend fun publish(queue: String, payload: String, messageId: String? = null) {
        val confirmed = CompletableFuture<Void>()
        producer.onNext(Message.of(payload)
            .addMetadata(SqliteOutgoingMetadata(messageId = messageId, queue = queue))
            .withAck { confirmed.complete(null); confirmed }
            .withNack { confirmed.completeExceptionally(it); confirmed })
        confirmed.await()
    }

    // The inherited suite uses runTest. Timeouts for the real broker use wall time.
    suspend fun receive(queue: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val message = CompletableFuture<Message<*>>()
        val ready = CompletableFuture<Flow.Subscription>()
        connector.getPublisher(config(queue)).subscribe(object : Flow.Subscriber<Message<*>> {
            override fun onSubscribe(subscription: Flow.Subscription) {
                ready.complete(subscription)
                subscription.request(1)
            }

            override fun onNext(item: Message<*>) { message.complete(item) }
            override fun onError(error: Throwable) { message.completeExceptionally(error) }
            override fun onComplete() { message.cancel(false) }
        })
        val subscription = ready.await()
        try {
            val incoming = withTimeoutOrNull(timeoutMs) { message.await() } ?: return@withContext null
            incoming.ack().await()
            incoming.payload as String
        } finally {
            subscription.cancel()
        }
    }

    fun clearQueues(vararg queues: String) {
        connection().use { conn ->
            conn.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            conn.prepareStatement("DELETE FROM lemline_messages WHERE queue = ?").use { stmt ->
                for (queue in queues) {
                    stmt.setString(1, queue)
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun isActive(queue: String, messageId: String): Boolean = connection().use { conn ->
        conn.prepareStatement("SELECT 1 FROM lemline_messages WHERE queue = ? AND message_id = ?").use { stmt ->
            stmt.setString(1, queue)
            stmt.setString(2, messageId)
            stmt.executeQuery().use { it.next() }
        }
    }

    private fun connection() = DriverManager.getConnection("jdbc:sqlite:${Path.of(file).toUri().toASCIIString()}")

    private fun config(queue: String) = SmallRyeConfigBuilder().withDefaultValues(mapOf(
        "path" to file,
        "channel-name" to "test-client",
        "queue" to queue,
        "batch-size" to "1",
        "poll-interval" to "30",
    )).build()

    override fun close() = connector.destroy()
}
