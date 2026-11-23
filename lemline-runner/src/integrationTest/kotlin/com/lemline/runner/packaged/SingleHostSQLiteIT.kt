// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.packaged

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.gateway.v1.GatewayServiceGrpc
import com.lemline.gateway.v1.GetInstanceRequest
import com.lemline.gateway.v1.InstanceStatus
import com.lemline.gateway.v1.ListInstanceEventsRequest
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.gateway.v1.StartInstanceResult
import com.lemline.runner.common.config.SQLiteDatabase
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

internal class SingleHostSQLiteIT {
    @TempDir
    lateinit var directory: Path

    @AfterEach
    fun releaseWindowsTestFiles() {
        if (!System.getProperty("os.name").startsWith("Windows")) return

        // A stopped child can leave a SQLite file temporarily unavailable for
        // deletion on Windows. Wait for the handles before JUnit removes the directory.
        val paths = Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).toList() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        for (path in paths) {
            if (path == directory) continue
            while (true) {
                try {
                    Files.deleteIfExists(path)
                    break
                } catch (failure: FileSystemException) {
                    if (System.nanoTime() >= deadline) throw failure
                    Thread.sleep(100)
                }
            }
        }
    }

    @Test
    fun `gateway workflow resumes after restart and from a three file backup`() {
        Fixture(directory.resolve("original")).use { source ->
            source.define("alpha", "listener", listener)
            source.define("bravo", "listener", listener)
            source.define("alpha", "emitter", """
                do:
                  - pause:
                      wait: PT18S
                  - emitPayload:
                      emit:
                        event:
                          with:
                            source: https://lemline.test/packaged
                            type: test.single-host.sqlite
                            data: { value: persisted }
            """.trimIndent())

            source.startGateway()
            source.startWorker()
            val alpha = source.start("alpha", "listener")
            val bravo = source.start("bravo", "listener")
            source.await("both listeners persisted") {
                source.count("orchestration.db", "SELECT count(*) FROM lemline_listeners") == 2
            }
            val emitter = source.start("alpha", "emitter")
            source.await("emitter is waiting") {
                source.count("orchestration.db", "SELECT count(*) FROM lemline_waits WHERE workflow_id = '$emitter' AND outbox_completed_at IS NULL") == 1
            }
            source.await("gateway can read the pre-backup history") { source.history(emitter).isNotEmpty() }
            source.stopAll()
            assertEquals(1, source.count("orchestration.db",
                "SELECT count(*) FROM lemline_waits WHERE workflow_id = '$emitter' AND outbox_completed_at IS NULL"))

            val backup = directory.resolve("restored")
            source.backupTo(backup)
            for (location in listOf(source.root, backup)) {
                Fixture(location).use { runner ->
                    runner.startGateway()
                    runner.startWorker()
                    runner.completed(emitter)
                    runner.completed(alpha)
                    val output = runner.scalar("analytics.db", """
                        SELECT payload FROM lemline_lifecycle_events WHERE lemline_workflow_id = '$alpha'
                        AND type = '${LifecycleEventType.WORKFLOW_COMPLETED.type}'
                    """.trimIndent())
                    assertEquals("persisted", ObjectMapper().readTree(assertNotNull(output)).at("/data/output/0/value").asText())
                    assertTrue(LifecycleEventType.WORKFLOW_STARTED.type in runner.history(emitter))
                    assertEquals(0, runner.terminalCount(bravo))
                    assertEquals(1, runner.count("orchestration.db", "SELECT count(*) FROM lemline_listeners WHERE workflow_id = '$bravo'"))
                }
            }
        }
    }

    @Test
    fun `outbox recovery deduplicates an active publication after a crash`() = recoverPublication(consumeBeforeRetry = false)

    @Test
    fun `outbox recovery republishes a consumed message using the same logical ID`() = recoverPublication(consumeBeforeRetry = true)

    private fun recoverPublication(consumeBeforeRetry: Boolean) {
        Fixture(directory.resolve("publication")).use { fixture ->
            fixture.define("alpha", "waiter", """
                do:
                  - pause:
                      wait: PT1S
                  - done:
                      set: { result: recovered }
            """.trimIndent())
            fixture.startGateway()
            // The real transport commits first. Guard the next database write so
            // the parent can observe that boundary before killing the publisher.
            fixture.execute("orchestration.db", """
                CREATE TRIGGER interrupt_completion BEFORE UPDATE OF outbox_completed_at
                ON lemline_gateway_command_outbox WHEN NEW.outbox_completed_at IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'test: relay finalization interrupted'); END
            """.trimIndent())
            val id = fixture.start("alpha", "waiter")
            val logicalId = assertNotNull(fixture.scalar("orchestration.db",
                "SELECT id FROM lemline_gateway_command_outbox WHERE workflow_id = '$id'"))
            val queue = "single-host-alpha-commands"
            val delivery = "queue = '$queue' AND message_id = '$logicalId'"
            fixture.await("broker commit and failed orchestration update") {
                fixture.count("messaging.db", "SELECT count(*) FROM lemline_messages WHERE $delivery") == 1 &&
                    fixture.logsContain("test: relay finalization interrupted")
            }
            val first = fixture.scalar("messaging.db", "SELECT id FROM lemline_messages WHERE $delivery")
            fixture.stopAll(kill = true)
            assertEquals(1, fixture.count("orchestration.db",
                "SELECT count(*) FROM lemline_gateway_command_outbox WHERE workflow_id = '$id' AND outbox_completed_at IS NULL"))

            if (consumeBeforeRetry) {
                fixture.startWorker()
                fixture.await("the original delivery was consumed and the workflow completed") {
                    fixture.terminalCount(id) == 1 &&
                        fixture.count("messaging.db", "SELECT count(*) FROM lemline_messages WHERE $delivery") == 0
                }
                fixture.stopAll()
            }
            fixture.execute("orchestration.db", "DROP TRIGGER interrupt_completion")
            fixture.startGateway()
            fixture.await("restarted outbox finalized the replay") {
                fixture.count("orchestration.db",
                    "SELECT count(*) FROM lemline_gateway_command_outbox WHERE workflow_id = '$id' AND outbox_completed_at IS NOT NULL") == 1
            }
            assertEquals(1, fixture.count("messaging.db", "SELECT count(*) FROM lemline_messages WHERE queue = '$queue'"))
            val replay = assertNotNull(fixture.scalar("messaging.db", "SELECT id FROM lemline_messages WHERE $delivery"))
            if (consumeBeforeRetry) assertTrue(replay != first) else assertEquals(first, replay)
            fixture.startWorker()
            fixture.completed(id)
            fixture.await("logical start was acknowledged") {
                fixture.count("messaging.db", "SELECT count(*) FROM lemline_messages WHERE $delivery") == 0
            }
            assertEquals(1, fixture.count("orchestration.db", "SELECT count(*) FROM lemline_waits WHERE workflow_id = '$id'"))
            assertEquals(1, fixture.terminalCount(id))
        }
    }

    @Test
    fun `lifecycle event survives a crash after ingestion and before acknowledgement`() {
        Fixture(directory.resolve("ingestion")).use { fixture ->
            fixture.define("alpha", "example", "do:\n  - done:\n      set: { result: ingested }")
            fixture.startGateway()
            val eventId = "test-crash-before-ack"
            val payload = """{"specversion":"1.0","id":"$eventId","source":"urn:lemline:test","type":"test.lifecycle","data":{"value":"original"}}"""
            fixture.connection("messaging.db").use { conn ->
                conn.prepareStatement("""
                    INSERT INTO lemline_messages (queue, message_id, payload, enqueued_at, available_at)
                    VALUES ('lemline-lifecycle-events', ?, ?, 0, 0)
                """.trimIndent()).use {
                    it.setString(1, eventId)
                    it.setBytes(2, payload.encodeToByteArray())
                    it.executeUpdate()
                }
            }
            fixture.execute("messaging.db", """
                CREATE TRIGGER interrupt_ack BEFORE DELETE ON lemline_messages
                WHEN OLD.message_id = '$eventId'
                BEGIN SELECT RAISE(ABORT, 'test: lifecycle ack interrupted'); END
            """.trimIndent())
            fixture.startWorker()
            val selected = "FROM lemline_lifecycle_events WHERE source = 'urn:lemline:test' AND event_id = '$eventId'"
            fixture.await("analytics commit and failed message acknowledgement") {
                fixture.count("analytics.db", "SELECT count(*) $selected") == 1 &&
                    fixture.logsContain("test: lifecycle ack interrupted")
            }
            val original = assertNotNull(fixture.scalar("analytics.db", "SELECT payload $selected"))
            val lease = assertNotNull(fixture.scalar("messaging.db", "SELECT lease_id FROM lemline_messages WHERE message_id = '$eventId'"))
            fixture.stopAll(kill = true)
            fixture.execute("messaging.db", "DROP TRIGGER interrupt_ack")
            // The trigger records the token of the delivery that finally settles.
            fixture.execute("messaging.db", "CREATE TABLE test_settlement (lease_id TEXT NOT NULL)")
            fixture.execute("messaging.db", """
                CREATE TRIGGER audit_ack BEFORE DELETE ON lemline_messages WHEN OLD.message_id = '$eventId'
                BEGIN INSERT INTO test_settlement VALUES (OLD.lease_id); END
            """.trimIndent())
            fixture.startWorker()
            fixture.await("message was acknowledged after redelivery") {
                fixture.count("messaging.db", "SELECT count(*) FROM test_settlement") == 1
            }
            assertTrue(fixture.scalar("messaging.db", "SELECT lease_id FROM test_settlement") != lease)
            assertEquals(1, fixture.count("analytics.db", "SELECT count(*) $selected"))
            assertEquals(original, fixture.scalar("analytics.db", "SELECT payload $selected"))
            assertEquals("original", ObjectMapper().readTree(original).at("/data/value").asText())
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SQLITE_COMMAND_BENCHMARK", matches = "1")
    fun `measure gateway command latency during an analytics burst`() {
        Fixture(directory.resolve("measurement")).use { fixture ->
            fixture.define("alpha", "benchmark", "do:\n  - done:\n      set: { result: measured }")
            fixture.startGateway()
            fixture.startWorker()
            fixture.execute("messaging.db", "CREATE TABLE command_latency (message_id TEXT, latency_ms INTEGER)")
            fixture.execute("messaging.db", """
                CREATE TRIGGER record_latency AFTER DELETE ON lemline_messages
                WHEN OLD.queue = 'single-host-alpha-commands'
                BEGIN INSERT INTO command_latency VALUES (
                    OLD.message_id, CAST(unixepoch('subsec') * 1000 AS INTEGER) - OLD.enqueued_at
                ); END
            """.trimIndent())
            repeat(10) { fixture.completed(fixture.start("alpha", "benchmark")) }
            val executor = Executors.newSingleThreadExecutor()
            try {
                fun measure(spike: Boolean) {
                    val sampleSize = 160
                    val startedAt = System.nanoTime()
                    val load = if (spike) executor.submit<Int> { fixture.enqueueAnalyticsBurst() } else null
                    val ids = List(sampleSize) { index ->
                        pauseUntil(startedAt + TimeUnit.MILLISECONDS.toNanos(index * 50L))
                        fixture.start("alpha", "benchmark")
                    }
                    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    val query = "SELECT id FROM lemline_gateway_command_outbox WHERE workflow_id IN (" +
                        ids.joinToString(",") { "'$it'" } + ")"
                    val messageIds = fixture.connection("orchestration.db").use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.executeQuery(query).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                        }
                    }
                    assertEquals(sampleSize, messageIds.size)
                    val filter = "FROM command_latency WHERE message_id IN (" +
                        messageIds.joinToString(",") { "'$it'" } + ")"
                    fixture.await("command latency samples") {
                        fixture.count("messaging.db", "SELECT count(DISTINCT message_id) $filter") == sampleSize
                    }
                    val samples = fixture.connection("messaging.db").use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.executeQuery("SELECT min(latency_ms) AS latency $filter GROUP BY message_id ORDER BY latency")
                                .use { rows -> buildList { while (rows.next()) add(rows.getLong(1)) } }
                        }
                    }
                    val ingestedDuringCommands = fixture.count("analytics.db",
                        "SELECT count(*) FROM lemline_lifecycle_events WHERE source = 'urn:lemline:benchmark'")
                    val submitted = load?.get(45, TimeUnit.SECONDS) ?: 0
                    if (spike) fixture.await("synthetic analytics backlog drained") {
                        fixture.count("analytics.db",
                            "SELECT count(*) FROM lemline_lifecycle_events WHERE source = 'urn:lemline:benchmark'") == submitted
                    }
                    fun percentile(p: Int) = samples[(samples.size * p + 99) / 100 - 1]
                    println("SQLite benchmark: spike=$spike commands=$sampleSize command_window_ms=$durationMs " +
                        "synthetic_submitted=$submitted synthetic_ingested_at_sample_read=$ingestedDuringCommands " +
                        "p50_ms=${percentile(50)} p95_ms=${percentile(95)} p99_ms=${percentile(99)}")
                }
                measure(spike = false)
                measure(spike = true)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private class Fixture(val root: Path) : AutoCloseable {
        private val config = root.resolve("lemline.yaml")
        private val processLogs = Files.createTempDirectory(
            Files.createDirectories(Path.of("build", "packaged-process-logs")), "single-host-",
        )
        private val children = mutableListOf<Pair<Process, Path>>()
        private var channel: ManagedChannel? = null

        init {
            Files.createDirectories(root)
            Files.writeString(config, """
                lemline:
                  namespaces: [alpha, bravo]
                  database:
                    type: sqlite
                    migrate-at-start: true
                    # A relay killed while publishing is taken over when its claim expires.
                    sqlite: { outbox-claim: 3s }
                  analytics:
                    type: sqlite
                    migrate-at-start: true
                  messaging:
                    type: sqlite
                    prefix: single-host
                    sqlite: { visibility-timeout: 4 }
                  gateway:
                    grpc: { host: 127.0.0.1 }
                    tls: { enabled: false }
                    authentication: { enabled: false }
                  outbox:
                    gateway:
                      outbox: { every: 1s, initial-jitter: 0s }
                    wait:
                      outbox: { every: 1s, initial-jitter: 0s }
            """.trimIndent())
            println("Single-host SQLite process logs: ${processLogs.toAbsolutePath()}")
        }

        fun define(namespace: String, name: String, body: String) {
            val workflow = root.resolve("$namespace-$name.yaml")
            Files.writeString(workflow, """
                document:
                  dsl: '1.0.0'
                  namespace: $namespace
                  name: $name
                  version: '1.0.0'
            """.trimIndent() + "\n" + body)
            val (code, output) = PackagedEnvironment.run("definition", "post", "-f", "$workflow", configFile = config)
            assertEquals(0, code, output)
        }

        fun startGateway() {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val (process, log) = launch("gateway", "gateway", "start", "--grpc-port", "$port", "--metrics-port", "0")
            await("gateway startup") { process.isAlive && logText(log).contains("Starting gRPC gateway server") }
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
        }

        fun startWorker() {
            val (process, log) = launch("worker", "listen", "--metrics-port", "0")
            await("worker startup") { process.isAlive && logText(log).contains("started in") }
        }

        private fun launch(label: String, vararg args: String): Pair<Process, Path> {
            val log = Files.createTempFile(processLogs, "$label-", ".log")
            val process = ProcessBuilder(PackagedEnvironment.command("--info", *args, configFile = config))
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            return (process to log).also { children += it }
        }

        fun logsContain(text: String): Boolean = children.any { (_, log) -> text in logText(log) }

        // These markers are ASCII. The child can use a Windows code page or be
        // partway through writing a multibyte character when we poll its log.
        private fun logText(log: Path): String = Files.readAllBytes(log).decodeToString()

        private fun gateway() = GatewayServiceGrpc.newBlockingStub(checkNotNull(channel))
            .withWaitForReady().withDeadlineAfter(5, TimeUnit.SECONDS)

        fun start(namespace: String, name: String): String {
            val reply = gateway().startInstance(StartInstanceRequest.newBuilder().setNamespace(namespace).setName(name).build())
            assertEquals(StartInstanceResult.START_INSTANCE_RESULT_ACCEPTED_NEW, reply.result)
            return reply.instanceId
        }

        fun history(id: String): List<String> = try {
            gateway().listInstanceEvents(
                ListInstanceEventsRequest.newBuilder().setInstanceId(id).build(),
            ).eventsList.map { it.eventType }
        } catch (failure: StatusRuntimeException) {
            if (failure.status.code != Status.Code.NOT_FOUND) throw failure
            emptyList()
        }

        fun completed(id: String) {
            await("workflow $id completed") { terminalCount(id) == 1 }
            assertEquals(InstanceStatus.INSTANCE_STATUS_COMPLETED,
                gateway().getInstance(GetInstanceRequest.newBuilder().setInstanceId(id).build()).instance.status)
            assertTrue(LifecycleEventType.WORKFLOW_COMPLETED.type in history(id))
        }

        fun terminalCount(id: String) = count("analytics.db", """
            SELECT count(*) FROM lemline_lifecycle_events WHERE lemline_workflow_id = '$id'
            AND type IN ('${LifecycleEventType.WORKFLOW_COMPLETED.type}', '${LifecycleEventType.WORKFLOW_FAULTED.type}')
        """.trimIndent())

        fun connection(file: String): Connection {
            val path = root.resolve(file)
            check(Files.isRegularFile(path)) { "Missing database $path" }
            return DriverManager.getConnection(SQLiteDatabase.jdbcUrl(path))
        }

        fun enqueueAnalyticsBurst(): Int {
            val startedAt = System.nanoTime()
            val batchSize = 20
            val batches = 160
            connection("messaging.db").use { conn ->
                conn.autoCommit = false
                conn.prepareStatement("""
                    INSERT INTO lemline_messages (queue, message_id, payload, enqueued_at, available_at)
                    VALUES ('lemline-lifecycle-events', ?, ?, 0, 0)
                """.trimIndent()).use { insert ->
                    repeat(batches) { batch ->
                        pauseUntil(startedAt + TimeUnit.MILLISECONDS.toNanos(batch * 50L))
                        repeat(batchSize) { offset ->
                            val id = "benchmark-${batch * batchSize + offset}"
                            insert.setString(1, id)
                            insert.setBytes(2, """{"specversion":"1.0","id":"$id","source":"urn:lemline:benchmark","type":"test.burst"}""".encodeToByteArray())
                            insert.addBatch()
                        }
                        insert.executeBatch()
                        conn.commit()
                    }
                }
            }
            return batchSize * batches
        }

        fun execute(file: String, sql: String) = connection(file).use { conn -> conn.createStatement().use { it.execute(sql) } }
        fun count(file: String, sql: String) = assertNotNull(scalar(file, sql)).toInt()
        fun scalar(file: String, sql: String): String? = connection(file).use { conn ->
            conn.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

        fun backupTo(backup: Path) {
            check(children.isEmpty())
            Files.createDirectories(backup)
            for (file in listOf("orchestration.db", "messaging.db", "analytics.db")) {
                assertEquals("0", scalar(file, "PRAGMA wal_checkpoint(TRUNCATE)"))
                Files.copy(root.resolve(file), backup.resolve(file))
                assertEquals("ok", DriverManager.getConnection(SQLiteDatabase.jdbcUrl(backup.resolve(file))).use { conn ->
                    conn.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA quick_check").use { rows -> rows.next(); rows.getString(1) }
                    }
                })
            }
        }

        fun await(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            do {
                if (condition()) return
                Thread.sleep(50)
            } while (System.nanoTime() < deadline && children.all { it.first.isAlive })
            error("Expected $label. Process logs: ${processLogs.toAbsolutePath()}")
        }

        fun stopAll(kill: Boolean = false) {
            channel?.shutdownNow()?.awaitTermination(5, TimeUnit.SECONDS)
            channel = null
            children.asReversed().forEach { (process, log) ->
                if (kill) process.destroyForcibly() else process.destroy()
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(10, TimeUnit.SECONDS)) { "Could not stop process: $log" }
                }
            }
            children.clear()
        }

        override fun close() = stopAll()
    }

    private val listener = """
        do:
          - listenForPayload:
              listen:
                to:
                  one:
                    with:
                      type: test.single-host.sqlite
    """.trimIndent()
}

private fun pauseUntil(deadline: Long) {
    val remaining = deadline - System.nanoTime()
    if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
}
