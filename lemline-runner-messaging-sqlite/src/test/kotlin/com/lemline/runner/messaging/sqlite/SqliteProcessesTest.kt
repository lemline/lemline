// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.sqlite

import com.lemline.runner.messaging.sqlite.connector.SqliteConnector
import com.lemline.runner.messaging.sqlite.connector.SqliteOutgoingMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class SqliteProcessesTest {
    @Test
    fun `two processes reserve disjoint batches and a killed worker's lease expires`() = runBlocking {
        val dir = processDirectory()
        val path = dir.resolve("messaging.db")
        val queue = SqliteDatabase.initialize(path)
        repeat(24) { queue.publish("work", "work-$it", "$it".encodeToByteArray(), 0) }
        val gate = dir.resolve("go")
        val first = startWorker(dir, "first", "hold", "12", "4")
        val second = startWorker(dir, "second", "hold", "12", "4")
        try {
            first.line("READY")
            second.line("READY")
            Files.createFile(gate)
            val left = first.line("RESERVED ").removePrefix("RESERVED ").split(",").toSet()
            val right = second.line("RESERVED ").removePrefix("RESERVED ").split(",").toSet()
            assertEquals(12, left.size)
            assertEquals(12, right.size)
            assertTrue(left.intersect(right).isEmpty())
            assertEquals((0 until 24).map { "work-$it" }.toSet(), left + right)
            assertTrue(queue.reserve("work", 24, 3).isEmpty())
            first.kill()
            Files.createFile(dir.resolve("ack"))
            second.line("ACKED")

            val redelivered = mutableSetOf<String>()
            withTimeout(7_000) {
                // Delivery renews each lease separately, so a batch can return in parts.
                while (redelivered.size < left.size) {
                    queue.reserve("work", 24, 3).forEach {
                        assertEquals(1, it.metadata.readCount)
                        assertTrue(redelivered.add(it.metadata.messageId))
                        assertTrue(queue.beginDelivery(it, 3) != null && queue.ack(it))
                    }
                    if (redelivered.size < left.size) delay(20)
                }
            }
            assertEquals(left, redelivered)
        } finally {
            first.kill()
            second.kill()
        }
    }

    @Test
    fun `late ack release and dead-letter leave a delivery reassigned to another process intact`() = runBlocking {
        val dir = processDirectory()
        val queue = SqliteDatabase.initialize(dir.resolve("messaging.db"))
        queue.publish("work", "original", "work".encodeToByteArray(), 0)
        val old = queue.reserve("work", 1, 1).single()
        val worker = startWorker(dir, "reassigned", "hold", "1", "30")
        try {
            worker.line("READY")
            Files.createFile(dir.resolve("go"))
            assertEquals("RESERVED original", worker.line("RESERVED "))
            assertFalse(queue.ack(old))
            assertFalse(queue.release(old))
            assertFalse(queue.deadLetter(old, "work.dlq", "stale"))
            assertTrue(queue.reserve("work", 1, 1).isEmpty())
            assertTrue(queue.reserve("work.dlq", 1, 1).isEmpty())
            Files.createFile(dir.resolve("ack"))
            worker.line("ACKED")
            assertTrue(queue.reserve("work", 1, 1).isEmpty())
        } finally {
            worker.kill()
        }
    }

    @ParameterizedTest(name = "first copy consumed before retry = {0}")
    @ValueSource(booleans = [false, true])
    fun `retry after confirmation loss preserves the logical message identity`(consumedBeforeRetry: Boolean) = runBlocking {
        val dir = processDirectory()
        val path = dir.resolve("messaging.db")
        val queue = SqliteDatabase.initialize(path)
        val worker = startWorker(dir, "lost-confirmation", "lose-confirmation")
        try {
            worker.line("COMMITTED; confirmation pending")
            assertTrue(worker.process.waitFor(8, TimeUnit.SECONDS))
            assertEquals(42, worker.process.exitValue())
            val first = queue.reserve("work", 1, 10).single()
            assertEquals("stable", first.metadata.messageId)
            assertEquals("original", first.payload.decodeToString())

            val retry = SqliteConnector()
            try {
                val producer = retry.producer(path)
                if (consumedBeforeRetry) assertTrue(queue.ack(first))
                publish(producer, "work", "stable", "retry")
                if (consumedBeforeRetry) {
                    val replay = queue.reserve("work", 1, 10).single()
                    assertEquals(first.metadata.messageId, replay.metadata.messageId)
                    assertNotEquals(first.rowId, replay.rowId)
                    assertEquals("retry", replay.payload.decodeToString())
                    assertFalse(queue.ack(first))
                    assertTrue(queue.ack(replay))
                } else {
                    assertTrue(queue.ack(first))
                }
                assertTrue(queue.reserve("work", 1, 1).isEmpty())
            } finally {
                retry.destroy()
            }
        } finally {
            worker.kill()
        }
    }

    @Test
    fun `a second process publishing to a busy queue cannot starve another queue`() = runBlocking {
        val dir = processDirectory()
        val path = dir.resolve("messaging.db")
        val worker = startWorker(dir, "producer-under-load", "produce-under-load")
        val connector = SqliteConnector()
        try {
            worker.line("LOADED")
            assertTrue(worker.process.isAlive)
            publish(connector.producer(path), "cold", "cold-1", "cold")
            val receiver = connector.consume(path, "queues" to "hot,cold", "batch-size" to "4")
            val batchAndCold = List(5) { receiver.take() }
            assertEquals(listOf("hot", "hot", "hot", "hot", "cold"), batchAndCold.map { it.sqlite.queue })
            assertEquals("cold-1", batchAndCold.last().sqlite.messageId)
            batchAndCold.forEach { it.ack().await() }
        } finally {
            connector.destroy()
            worker.kill()
        }
    }

    private fun processDirectory(): Path = Files.createTempDirectory("lemline-sqlite-process-").also {
        println("SQLite subprocess logs: ${it.toRealPath()}")
    }

    private fun startWorker(dir: Path, name: String, vararg args: String): Worker {
        val log = dir.resolve("$name.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("sqlite.test.classpath"),
            SqliteProcessWorker::class.java.name, dir.toString(), *args,
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        return Worker(process, log)
    }

    private data class Worker(val process: Process, val log: Path) {
        suspend fun line(prefix: String): String {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (true) {
                val output = log.readText()
                output.lineSequence().firstOrNull { it.startsWith(prefix) }?.let { return it }
                check(process.isAlive && System.nanoTime() < end) { "Missing '$prefix' in $log:\n$output" }
                delay(20)
            }
        }

        fun kill() {
            if (process.isAlive) process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "SQLite worker did not stop: $log" }
        }
    }
}

internal object SqliteProcessWorker {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val dir = Path.of(args[0])
        val path = SqliteDatabase.resolve(dir.resolve("messaging.db").toString())
        when (args[1]) {
            "hold" -> {
                val queue = SqliteDatabase.initialize(path)
                println("READY")
                while (!Files.exists(dir.resolve("go"))) delay(10)
                val batchSize = args[2].toInt()
                val visibility = args[3].toInt()
                val deliveries = withTimeout(12_000) {
                    var batch = queue.reserve("work", batchSize, visibility)
                    while (batch.isEmpty()) {
                        delay(10)
                        batch = queue.reserve("work", batchSize, visibility)
                    }
                    batch
                }
                check(deliveries.all { queue.beginDelivery(it, visibility) != null })
                println("RESERVED " + deliveries.joinToString(",") { it.metadata.messageId })
                while (!Files.exists(dir.resolve("ack"))) delay(10)
                check(deliveries.all { queue.ack(it) })
                println("ACKED")
            }
            "produce-under-load" -> {
                val connector = SqliteConnector()
                val outgoing = connector.producer(path)
                repeat(50) { publish(outgoing, "hot", "hot-$it", "payload-$it") }
                println("LOADED")
                withTimeout(15_000) {
                    var next = 50
                    while (true) {
                        publish(outgoing, "hot", "hot-$next", "payload-$next")
                        next++
                        delay(10)
                    }
                }
            }
            "lose-confirmation" -> {
                val connector = SqliteConnector()
                connector.producer(path).onNext(Message.of("original")
                    .addMetadata(SqliteOutgoingMetadata("stable", "work"))
                    .withAck {
                        println("COMMITTED; confirmation pending")
                        System.out.flush()
                        // Kill this process before the publisher's confirmation can complete.
                        Runtime.getRuntime().halt(42)
                        error("unreachable")
                    })
                delay(15_000)
                error("Publication never reached its confirmation callback")
            }
            else -> error("Unknown worker action ${args[1]}")
        }
    }
}
