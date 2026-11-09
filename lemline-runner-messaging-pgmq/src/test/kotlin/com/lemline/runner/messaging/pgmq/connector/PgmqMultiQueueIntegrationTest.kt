// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.pgmq.connector

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.messaging.pgmq.DlqMessage
import com.lemline.runner.messaging.pgmq.PgmqClient
import com.lemline.runner.messaging.pgmq.PgmqMessage
import com.lemline.runner.messaging.pgmq.config.PgmqConnectorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.DriverManager
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.SubmissionPublisher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Integration tests for the PGMQ connector namespace-isolation features:
 * - Outgoing per-message queue override via [PgmqOutgoingMetadata.queue]
 * - Incoming multi-queue polling via the `queues` channel attribute
 * - Per-source-queue dead-lettering (`<source-queue>.dlq`) in multi-queue mode
 */
@RequiresDocker
class PgmqMultiQueueIntegrationTest : FunSpec({

    val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test")

    beforeSpec {
        postgres.start()

        // Run SQL-only PGMQ migrations
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            listOf(
                "db/migration/postgresql/V800__Create_pgmq_schema.sql",
                "db/migration/postgresql/V801__Create_pgmq_functions.sql",
                "db/migration/postgresql/V802__Lemline_pgmq_deduplication.sql",
            ).forEach { resource ->
                val sql = PgmqMultiQueueIntegrationTest::class.java.classLoader
                    .getResourceAsStream(resource)!!
                    .bufferedReader().readText()
                conn.createStatement().execute(sql)
            }
        }
    }

    afterSpec {
        postgres.stop()
    }

    /** Builds a mock MicroProfile Config backed by a property map. */
    fun mockConfig(props: Map<String, String>): Config {
        val config = mockk<Config>()
        every { config.getOptionalValue(any(), String::class.java) } answers {
            Optional.ofNullable(props[firstArg<String>()])
        }
        return config
    }

    /** Connection properties shared by every channel, prefixed for the given channel/direction. */
    fun channelProps(direction: String, channel: String, extra: Map<String, String>): Map<String, String> {
        val prefix = "mp.messaging.$direction.$channel"
        return mapOf(
            "$prefix.host" to postgres.host,
            "$prefix.port" to postgres.getMappedPort(5432).toString(),
            "$prefix.database" to postgres.databaseName,
            "$prefix.username" to postgres.username,
            "$prefix.password" to postgres.password,
        ) + extra.mapKeys { (k, _) -> "$prefix.$k" }
    }

    /** Creates a connector with an injected config and a channel-level config mock. */
    fun createConnector(props: Map<String, String>): PgmqConnector =
        PgmqConnector().apply { config = mockConfig(props) }

    fun channelConfig(channelName: String): Config {
        val cfg = mockk<Config>()
        every { cfg.getValue("channel-name", String::class.java) } returns channelName
        return cfg
    }

    /** A standalone client used to prepare queues and inspect their content. */
    fun helperClient(channel: String): PgmqClient {
        val props = channelProps("incoming", channel, mapOf("queue" to channel))
        return PgmqClient(PgmqConnectorConfig(mockConfig(props), channel))
    }

    /**
     * Polls a queue until a message shows up or the timeout expires.
     *
     * Read errors are tolerated while waiting: lazily-created queues (override queues,
     * derived DLQs) may not exist yet when polling starts.
     */
    suspend fun awaitMessage(client: PgmqClient, queue: String, timeoutMs: Long = 15_000): PgmqMessage? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val messages = client.read(batchSize = 1, queue = queue)
                if (messages.isNotEmpty()) return messages.first()
            } catch (_: Exception) {
                // Queue not created yet - keep polling
            }
            delay(200)
        }
        return null
    }

    /** Flow subscriber that collects incoming messages and acks or nacks them. */
    class CollectingSubscriber(
        expected: Int,
        private val nackAll: Boolean = false,
    ) : Flow.Subscriber<Message<*>> {
        val received = CopyOnWriteArrayList<Message<*>>()
        val latch = CountDownLatch(expected)
        private var subscription: Flow.Subscription? = null

        override fun onSubscribe(s: Flow.Subscription) {
            subscription = s
            s.request(1)
        }

        override fun onNext(message: Message<*>) {
            received.add(message)
            if (nackAll) message.nack(RuntimeException("simulated processing failure")) else message.ack()
            latch.countDown()
            subscription?.request(1)
        }

        override fun onError(t: Throwable) {}
        override fun onComplete() {}
        fun cancel() = subscription?.cancel()
    }

    test("outgoing metadata queue override should send message to the override queue") {
        val channel = "out-override-channel"
        val connector = createConnector(
            channelProps("outgoing", channel, mapOf("queue" to "out-default-q"))
        )
        val helper = helperClient("out-helper")

        try {
            @Suppress("UNCHECKED_CAST")
            val subscriber = connector.getSubscriber(channelConfig(channel)) as Flow.Subscriber<Message<*>>

            val publisher = SubmissionPublisher<Message<*>>()
            publisher.subscribe(subscriber)

            // One message with a queue override (and a messageId header), one without
            val overridden = Message.of(
                """{"target":"override"}""",
                Metadata.of(PgmqOutgoingMetadata(messageId = "dedup-123", queue = "out-override-q"))
            )
            val regular = Message.of("""{"target":"default"}""")
            publisher.submit(overridden)
            publisher.submit(regular)

            // The override queue is created lazily on first use; the message must land there
            val overrideMessage = awaitMessage(helper, "out-override-q")
            overrideMessage.shouldNotBeNull()
            overrideMessage.message shouldBe """{"target":"override"}"""
            overrideMessage.headers.shouldNotBeNull() shouldContain "dedup-123"

            // The regular message must still land in the channel's configured queue
            val defaultMessage = awaitMessage(helper, "out-default-q")
            defaultMessage.shouldNotBeNull()
            defaultMessage.message shouldBe """{"target":"default"}"""

            publisher.close()
        } finally {
            helper.close()
            connector.destroy()
        }
    }

    test("incoming queues attribute should poll all listed queues and tag the source queue") {
        val channel = "multi-in-channel"
        val connector = createConnector(
            channelProps(
                "incoming", channel,
                mapOf(
                    "queues" to "multi-q-a, multi-q-b",
                    "poll-interval" to "100",
                    "batch-size" to "5",
                )
            )
        )
        val helper = helperClient("multi-helper")

        try {
            // Prepare both queues and enqueue one message in each
            helper.ensureQueue("multi-q-a")
            helper.ensureQueue("multi-q-b")
            helper.send("""{"from":"a"}""", queue = "multi-q-a")
            helper.send("""{"from":"b"}""", queue = "multi-q-b")

            val subscriber = CollectingSubscriber(expected = 2)
            connector.getPublisher(channelConfig(channel)).subscribe(subscriber)

            subscriber.latch.await(20, TimeUnit.SECONDS) shouldBe true
            subscriber.received shouldHaveSize 2

            // Each message must carry the queue it was actually read from
            val byPayload = subscriber.received.associate { message ->
                val metadata = message.metadata.filterIsInstance<PgmqIncomingMetadata>().first()
                message.payload as String to metadata.queue
            }
            byPayload.keys shouldContainExactlyInAnyOrder listOf("""{"from":"a"}""", """{"from":"b"}""")
            byPayload["""{"from":"a"}"""] shouldBe "multi-q-a"
            byPayload["""{"from":"b"}"""] shouldBe "multi-q-b"

            subscriber.cancel()
        } finally {
            helper.close()
            connector.destroy()
        }
    }

    test("message exceeding max retries in multi-queue mode should go to the derived per-source DLQ") {
        val channel = "dlq-multi-channel"
        val connector = createConnector(
            channelProps(
                "incoming", channel,
                mapOf(
                    "queues" to "dlq-q-a,dlq-q-b",
                    "poll-interval" to "100",
                    "max-retries" to "1",
                    // Explicit shared DLQ must be ignored in multi-queue mode
                    "dead-letter-queue" to "shared-dlq",
                )
            )
        )
        val helper = helperClient("dlq-helper")

        try {
            helper.ensureQueue("dlq-q-a")
            helper.ensureQueue("dlq-q-b")
            helper.send("""{"poison":true}""", queue = "dlq-q-a")

            // Nack every delivery: with max-retries=1 the first nack dead-letters the message
            val subscriber = CollectingSubscriber(expected = 1, nackAll = true)
            connector.getPublisher(channelConfig(channel)).subscribe(subscriber)

            subscriber.latch.await(20, TimeUnit.SECONDS) shouldBe true

            // The DLQ is derived from the source queue and auto-created on first use
            val dlqMessage = awaitMessage(helper, "dlq-q-a.dlq")
            dlqMessage.shouldNotBeNull()

            val dlqPayload = Json.decodeFromString<DlqMessage>(dlqMessage.message)
            dlqPayload.originalQueue shouldBe "dlq-q-a"
            dlqPayload.payload shouldBe """{"poison":true}"""
            dlqPayload.error shouldContain "simulated processing failure"

            // The message must be gone from the source queue
            helper.read(batchSize = 1, queue = "dlq-q-a") shouldHaveSize 0

            subscriber.cancel()
        } finally {
            helper.close()
            connector.destroy()
        }
    }
})
