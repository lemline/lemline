// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.pgmq

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.messaging.pgmq.config.PgmqConnectorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.sql.DriverManager
import java.util.*
import kotlinx.coroutines.delay
import org.eclipse.microprofile.config.Config
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Integration tests for PGMQ client using Testcontainers.
 *
 * These tests verify the actual PGMQ functionality with a real PostgreSQL database
 * using the SQL-only PGMQ implementation (no extension required).
 */
@RequiresDocker
class PgmqIntegrationTest : FunSpec({

    // Standard PostgreSQL image - SQL-only PGMQ doesn't require extension
    val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test")

    beforeSpec {
        postgres.start()

        // Run SQL-only PGMQ migrations
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // Load and execute V800 (schema)
            val v800 = PgmqIntegrationTest::class.java.classLoader
                .getResourceAsStream("db/migration/postgresql/V800__Create_pgmq_schema.sql")!!
                .bufferedReader().readText()
            conn.createStatement().execute(v800)

            // Load and execute V801 (functions)
            val v801 = PgmqIntegrationTest::class.java.classLoader
                .getResourceAsStream("db/migration/postgresql/V801__Create_pgmq_functions.sql")!!
                .bufferedReader().readText()
            conn.createStatement().execute(v801)

            // Load and execute V802 (Lemline deduplication)
            val v802 = PgmqIntegrationTest::class.java.classLoader
                .getResourceAsStream("db/migration/postgresql/V802__Lemline_pgmq_deduplication.sql")!!
                .bufferedReader().readText()
            conn.createStatement().execute(v802)
        }
    }

    afterSpec {
        postgres.stop()
    }

    fun createConfig(queueName: String): PgmqConnectorConfig {
        val config = mockk<Config>()
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.host",
                String::class.java
            )
        } returns Optional.of(postgres.host)
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.port",
                String::class.java
            )
        } returns Optional.of(postgres.getMappedPort(5432).toString())
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.database",
                String::class.java
            )
        } returns Optional.of(postgres.databaseName)
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.username",
                String::class.java
            )
        } returns Optional.of(postgres.username)
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.password",
                String::class.java
            )
        } returns Optional.of(postgres.password)
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.queue",
                String::class.java
            )
        } returns Optional.of(queueName)
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.visibility-timeout",
                String::class.java
            )
        } returns Optional.of("30")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.poll-interval",
                String::class.java
            )
        } returns Optional.of("100")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.batch-size",
                String::class.java
            )
        } returns Optional.of("10")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.max-pool-size",
                String::class.java
            )
        } returns Optional.of("5")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.auto-create-queue",
                String::class.java
            )
        } returns Optional.of("true")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.dead-letter-queue",
                String::class.java
            )
        } returns Optional.of("$queueName-dlq")
        every {
            config.getOptionalValue(
                "mp.messaging.incoming.$queueName.max-retries",
                String::class.java
            )
        } returns Optional.of("3")
        // Fallback to outgoing (returns empty)
        every {
            config.getOptionalValue(
                match { it.startsWith("mp.messaging.outgoing.") },
                String::class.java
            )
        } returns Optional.empty()

        return PgmqConnectorConfig(config, queueName)
    }

    test("should initialize PGMQ and create queue") {
        val config = createConfig("init-test-queue")
        val client = PgmqClient(config)

        // Initialize should create the queue using SQL-only PGMQ functions
        client.initialize()

        // If we got here without exception, initialization succeeded
        client.close()
    }

    test("should send and receive messages") {
        val config = createConfig("send-receive-queue")
        val client = PgmqClient(config)
        client.initialize()

        // Send a message
        val payload = """{"key": "value", "number": 42}"""
        val msgId = client.send(payload)

        msgId shouldNotBe null

        // Read the message
        val messages = client.read(1)
        messages shouldHaveSize 1
        messages[0].message shouldBe payload
        messages[0].msgId shouldBe msgId
        messages[0].readCt shouldBe 1

        // Delete the message (acknowledge)
        val deleted = client.delete(msgId)
        deleted shouldBe true

        // Verify message is gone
        val messagesAfterDelete = client.read(1)
        messagesAfterDelete shouldHaveSize 0

        client.close()
    }

    test("should handle message visibility timeout") {
        val config = createConfig("vt-test-queue")
        val client = PgmqClient(config)
        client.initialize()

        // Send a message
        val payload = """{"test": "visibility"}"""
        val msgId = client.send(payload)

        // Read with short visibility timeout
        val messages = client.read(1)
        messages shouldHaveSize 1

        // Try to read again immediately - should be empty (message is invisible)
        val messagesSecondRead = client.read(1)
        messagesSecondRead shouldHaveSize 0

        // Clean up
        client.delete(msgId)
        client.close()
    }

    test("should archive messages") {
        val config = createConfig("archive-test-queue")
        val client = PgmqClient(config)
        client.initialize()

        // Send and archive a message
        val payload = """{"archive": "test"}"""
        val msgId = client.send(payload)

        val archived = client.archive(msgId)
        archived shouldBe true

        // Message should no longer be readable
        val messages = client.read(1)
        messages shouldHaveSize 0

        client.close()
    }

    test("should send messages with delay") {
        val config = createConfig("delay-test-queue")
        val client = PgmqClient(config)
        client.initialize()

        // Send with 2-second delay
        val payload = """{"delayed": true}"""
        val msgId = client.send(payload, delaySeconds = 2)

        // Message should not be visible immediately
        val messagesBeforeDelay = client.read(1)
        messagesBeforeDelay shouldHaveSize 0

        // Wait for delay
        delay(2500)

        // Message should now be visible
        val messagesAfterDelay = client.read(1)
        messagesAfterDelay shouldHaveSize 1
        messagesAfterDelay[0].msgId shouldBe msgId

        // Clean up
        client.delete(msgId)
        client.close()
    }

    test("should move messages to dead letter queue after max retries") {
        val config = createConfig("dlq-test-queue")
        val client = PgmqClient(config)
        client.initialize()

        // Send a message
        val payload = """{"dlq": "test"}"""
        val msgId = client.send(payload)

        // Simulate failed processing by moving to DLQ
        client.moveToDeadLetterQueue(msgId, payload, "Test error")

        // Original message should be gone
        val messagesInQueue = client.read(1)
        messagesInQueue shouldHaveSize 0

        client.close()
    }
})
