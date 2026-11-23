// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.packaged

import com.lemline.common.values.IDV7
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.gateway.v1.GatewayServiceGrpc
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.gateway.v1.StartInstanceResult
import com.lemline.runner.common.config.SQLiteDatabase
import com.lemline.runner.common.test.RequiresDocker
import io.grpc.ManagedChannelBuilder
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@RequiresDocker
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SQLitePackagedRunnerIT {
    private val port get() = PackagedEnvironment.postgresPort
    private val analyticsUrl get() = "jdbc:postgresql://localhost:$port/${PackagedEnvironment.ANALYTICS_DATABASE}"
    private val brokerDatabase = "lemline_orchestration_" + UUID.randomUUID().toString().take(8)
    // PGMQ historically stores some headers as JSON strings. The consumer sees the
    // decoded ID in both representations.
    private val pgmqMessageId = "((headers #>> '{}')::jsonb)->>'messageId'"
    private val brokerUrl get() = "jdbc:postgresql://localhost:$port/$brokerDatabase"

    @BeforeAll
    fun provisionExistingBackends() {
        assumeTrue(PackagedEnvironment.artifactCommand.size > 1, "SQLite orchestration is qualified on JVM")
        val migrations = Files.createTempDirectory(PackagedEnvironment.workDir, "pgmq-migrations-")
        for (name in listOf(
            "V800__Create_pgmq_schema.sql",
            "V801__Create_pgmq_functions.sql",
            "V802__Lemline_pgmq_deduplication.sql",
            "V803__Create_pgmq_queues.sql",
        )) {
            checkNotNull(javaClass.getResourceAsStream("/db/migration/postgresql/$name")).use {
                Files.copy(it, migrations.resolve(name))
            }
        }
        DriverManager.getConnection("jdbc:postgresql://localhost:$port/lemline", "postgres", "postgres").use {
            it.execute("CREATE DATABASE $brokerDatabase")
        }
        Flyway.configure()
            .dataSource(brokerUrl, "postgres", "postgres")
            .locations("filesystem:$migrations")
            .load()
            .migrate()
        Flyway.configure()
            .dataSource(analyticsUrl, "postgres", "postgres")
            .locations("classpath:db/migration/analytics/postgresql")
            .defaultSchema("lemline")
            .table("lemline_flyway_schema_history_analytics")
            .load()
            .migrate()
    }

    @Test
    fun `a durable wait survives stopping and restarting the runner`() = Fixture("wait-restart").use { fixture ->
        val namespace = "alpha"
        fixture.postDefinition(namespace, "PT16S")
        val gateway = fixture.startGateway("gateway")
        val first = fixture.worker("worker-first", namespace)
        val id = gateway.startWorkflow(namespace)
        gateway.startWorkflow(namespace, id, StartInstanceResult.START_INSTANCE_RESULT_ACCEPTED_EXISTING)
        fixture.eventually("wait is persisted and still pending") {
            fixture.sqliteScalar("SELECT count(*) FROM lemline_waits WHERE workflow_id = '$id' AND outbox_completed_at IS NULL") == "1"
        }
        first.stop()
        assertEquals("1", fixture.sqliteScalar(
            "SELECT count(*) FROM lemline_waits WHERE workflow_id = '$id' AND outbox_completed_at IS NULL",
        ))
        fixture.worker("worker-restarted", namespace)
        fixture.assertCompleted(namespace, id)
        fixture.eventually("the resumed wait was finalized") {
            fixture.sqliteScalar("SELECT count(*) FROM lemline_waits WHERE workflow_id = '$id' AND outbox_completed_at IS NOT NULL") == "1"
        }
        println("Durable wait restarted and completed: $id")
    }

    @Test
    fun `a killed process leaves a claim that expires and replay preserves its message ID`() = Fixture("crash-replay").use { fixture ->
        val namespace = "alpha"
        fixture.postDefinition(namespace, "PT1S")
        val gateway = fixture.startGateway("gateway-killed")
        val queue = fixture.queue(namespace)
        fixture.prepareQueue(queue)

        // Make finalization roll back after the real PGMQ send has committed. The claim that
        // precedes publication leaves outbox_completed_at empty and does not fire the trigger.
        fixture.sqliteExecute("""
            CREATE TRIGGER fail_finalization BEFORE UPDATE OF outbox_completed_at ON lemline_gateway_command_outbox
            WHEN NEW.outbox_completed_at IS NOT NULL
            BEGIN
                SELECT RAISE(ABORT, 'packaged test: finalization interrupted');
            END
        """.trimIndent())
        val id = gateway.startWorkflow(namespace)
        val expectedMessageId = assertNotNull(
            fixture.sqliteScalar("SELECT id FROM lemline_gateway_command_outbox WHERE workflow_id = '$id'"),
        )
        val queueTable = "pgmq.\"q_$queue\""
        fixture.eventually("PGMQ committed the original emission") {
            fixture.brokerScalar("SELECT $pgmqMessageId FROM $queueTable ORDER BY msg_id LIMIT 1") == expectedMessageId
        }
        val queueMessageId = assertNotNull(fixture.brokerScalar("SELECT min(msg_id) FROM $queueTable"))
        fixture.eventually("the SQLite finalization rolled back") {
            gateway.runner.logText().contains("packaged test: finalization interrupted")
        }

        // Block the next send at the broker, once the unrecorded claim has expired. The
        // PostgreSQL waiter shows that the row is being published again, and the free SQLite
        // writer that the claim does not hold it meanwhile.
        fixture.broker().use { lock ->
            lock.autoCommit = false
            lock.execute("LOCK TABLE $queueTable IN ACCESS EXCLUSIVE MODE")
            fixture.eventually("relay is waiting on the broker without holding the SQLite writer", 15) {
                lock.scalar("""
                    SELECT count(*) FROM pg_locks
                    WHERE relation = '$queueTable'::regclass AND NOT granted
                """.trimIndent())!!.toInt() > 0 && !fixture.writerReserved()
            }
            gateway.runner.stop(kill = true)
            lock.rollback()
        }

        assertEquals("1", fixture.sqliteScalar(
            "SELECT count(*) FROM lemline_gateway_command_outbox WHERE workflow_id = '$id' AND outbox_completed_at IS NULL",
        ))
        fixture.sqliteExecute("DROP TRIGGER fail_finalization")
        val recoveryStartedAt = System.nanoTime()
        fixture.startGateway("gateway-restarted")
        fixture.eventually("restarted gateway took over the expired claim", 15) {
            fixture.sqliteScalar(
                "SELECT count(*) FROM lemline_gateway_command_outbox WHERE workflow_id = '$id' AND outbox_completed_at IS NOT NULL",
            ) == "1"
        }
        assertEquals(expectedMessageId, fixture.brokerScalar("SELECT $pgmqMessageId FROM $queueTable WHERE msg_id = $queueMessageId"))
        assertEquals("0", fixture.brokerScalar("SELECT count(*) FROM $queueTable WHERE ($pgmqMessageId) IS DISTINCT FROM '$expectedMessageId'"))
        val copies = fixture.brokerScalar("SELECT count(*) FROM $queueTable")
        val recoveryMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recoveryStartedAt)
        assertTrue(recoveryMillis < 15_000, "Claim takeover took $recoveryMillis ms")
        println("Expired claim taken over in $recoveryMillis ms; $copies PGMQ copies carry $expectedMessageId")
        fixture.worker("worker", namespace)
        fixture.assertCompleted(namespace, id)
        fixture.eventually("all copies were acknowledged") {
            fixture.brokerScalar("SELECT count(*) FROM $queueTable") == "0"
        }
        assertEquals("1", fixture.sqliteScalar("SELECT count(*) FROM lemline_waits WHERE workflow_id = '$id'"))
    }

    @Test
    fun `two runners on the same file isolate namespaces and process PGMQ redelivery`() = Fixture("two-workers").use { fixture ->
        val namespaces = listOf("alpha", "bravo")
        namespaces.forEach { fixture.postDefinition(it, "PT2S") }
        fixture.postDefinition("unfollowed", "PT1S")
        val gateway = fixture.startGateway("gateway")
        val idleId = gateway.startWorkflow("unfollowed")
        val instances = namespaces.associateWith { namespace ->
            val queue = fixture.queue(namespace)
            fixture.prepareQueue(queue)
            fixture.auditQueueDeletes(queue)
            val id = gateway.startWorkflow(namespace)
            fixture.eventually("initial start committed to $queue") {
                fixture.brokerScalar("SELECT count(*) FROM pgmq.\"q_$queue\"") == "1"
            }
            val messageId = assertNotNull(fixture.brokerScalar("SELECT $pgmqMessageId FROM pgmq.\"q_$queue\""))
            // Simulate a consumer that took the message and exited without acking.
            assertEquals(messageId, fixture.brokerScalar("SELECT $pgmqMessageId FROM pgmq.read('$queue', 8, 1)"))
            val runner = fixture.worker("worker-$namespace", namespace)
            Triple(id, messageId, runner)
        }

        for ((namespace, instance) in instances) {
            val (id, messageId, runner) = instance
            fixture.assertCompleted(namespace, id)
            assertTrue(runner.process.isAlive, runner.logText())
            fixture.eventually("PGMQ acknowledged redelivery of $messageId") {
                fixture.brokerScalar(
                    "SELECT count(*) FROM ${fixture.auditTable} WHERE message_id = '$messageId' AND read_ct >= 2",
                ) == "1"
            }
            println("Worker ${runner.process.pid()} completed $id in $namespace after PGMQ redelivery")
            assertEquals("1", fixture.sqliteScalar(
                "SELECT count(*) FROM lemline_waits WHERE workflow_id = '$id' AND workflow_namespace = '$namespace' AND outbox_completed_at IS NOT NULL",
            ))
        }
        assertEquals("1", fixture.brokerScalar("SELECT count(*) FROM pgmq.\"q_${fixture.queue("unfollowed")}\" WHERE read_ct = 0"))
        assertEquals("0", fixture.sqliteScalar("SELECT count(*) FROM lemline_waits WHERE workflow_id = '$idleId'"))
    }

    private inner class Fixture(label: String) : AutoCloseable {
        private val dir = Files.createTempDirectory(PackagedEnvironment.workDir, "$label-")
        private val file = dir.resolve("orchestration.db")
        private val processLogs = Files.createTempDirectory(
            Files.createDirectories(Path.of("build", "packaged-process-logs")), "sqlite-pgmq-",
        )
        private val prefix = "s" + UUID.randomUUID().toString().take(8)
        private val processes = mutableListOf<Runner>()
        val auditTable = "public.${prefix}_acks"

        init {
            println("Packaged SQLite runner logs: ${processLogs.toAbsolutePath()}")
        }

        fun queue(namespace: String) = "$prefix-$namespace-commands"

        fun broker(): Connection = DriverManager.getConnection(brokerUrl, "postgres", "postgres")
        fun brokerScalar(sql: String): String? = broker().use { it.scalar(sql) }

        private fun sqlite(busyMs: Int = 5000): Connection =
            DriverManager.getConnection(SQLiteDatabase.jdbcUrl(file).replace("busy_timeout=5000", "busy_timeout=$busyMs"))

        fun sqliteScalar(sql: String): String? = sqlite().use { it.scalar(sql) }
        fun sqliteExecute(sql: String) = sqlite().use { it.execute(sql) }

        fun writerReserved(): Boolean = sqlite(25).use {
            try {
                it.execute("BEGIN IMMEDIATE")
                it.execute("ROLLBACK")
                false
            } catch (e: SQLException) {
                if (e.errorCode != 5) throw e
                true
            }
        }

        fun prepareQueue(queue: String) = broker().use {
            it.execute("SELECT pgmq.create('$queue')")
            it.execute("SELECT lemline.create_dedup_index('$queue')")
        }

        fun auditQueueDeletes(queue: String) = broker().use {
            it.execute("CREATE TABLE IF NOT EXISTS $auditTable (message_id TEXT, read_ct INTEGER)")
            it.execute("""
                CREATE OR REPLACE FUNCTION public.${prefix}_ack() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO $auditTable VALUES (${pgmqMessageId.replace("headers", "OLD.headers")}, OLD.read_ct);
                    RETURN OLD;
                END $$
            """.trimIndent())
            it.execute("""
                CREATE TRIGGER record_ack BEFORE DELETE ON pgmq."q_$queue"
                FOR EACH ROW EXECUTE FUNCTION public.${prefix}_ack()
            """.trimIndent())
        }

        fun assertCompleted(namespace: String, id: String) {
            val filter = "WHERE lemline_workflow_id = '$id' AND lemline_workflow_namespace = '$namespace'"
            val table = "lemline.lemline_lifecycle_events"
            eventually("workflow $id completed in $namespace", 40) {
                DriverManager.getConnection(analyticsUrl, "postgres", "postgres").use {
                    it.scalar("SELECT count(*) FROM $table $filter AND type = '${LifecycleEventType.WORKFLOW_COMPLETED.type}'") == "1"
                }
            }
            DriverManager.getConnection(analyticsUrl, "postgres", "postgres").use {
                assertEquals("0", it.scalar("SELECT count(*) FROM $table $filter AND type = '${LifecycleEventType.WORKFLOW_FAULTED.type}'"))
            }
        }

        fun postDefinition(namespace: String, duration: String) {
            val path = dir.resolve("$namespace.yaml")
            Files.writeString(path, """
                document:
                  dsl: '1.0.3'
                  namespace: $namespace
                  name: waiter
                  version: '1.0.0'
                do:
                  - pause:
                      wait: $duration
                  - finish:
                      set: { done: true }
            """.trimIndent())
            val runner = launch("register-$namespace", config("register-$namespace"), "definition", "post", "-f", path.toString())
            assertTrue(runner.process.waitFor(30, TimeUnit.SECONDS), runner.logText())
            assertEquals(0, runner.process.exitValue(), runner.logText())
            assertTrue(Files.isRegularFile(file))
        }

        private fun config(label: String, namespace: String? = null, gatewayPort: Int? = null): Path =
            dir.resolve("$label-config.yaml").also { path ->
                val configuration = """
                    database:
                      type: sqlite
                      migrate-at-start: ${!Files.exists(file)}
                      sqlite: { path: orchestration.db, outbox-claim: 3s }
                    messaging:
                      type: pgmq
                      prefix: $prefix
                      pgmq:
                        host: localhost
                        port: $port
                        database: $brokerDatabase
                        username: postgres
                        password: postgres
                        commands:
                          consumer: { visibility-timeout: 5 }
                      lifecycleevents:
                        producer: { enabled: true }
                        consumer: { enabled: true }
                    analytics:
                      type: postgresql
                      postgresql:
                        host: localhost
                        port: $port
                        database: ${PackagedEnvironment.ANALYTICS_DATABASE}
                        username: postgres
                        password: postgres
                    gateway:
                      grpc: { host: 127.0.0.1, port: ${gatewayPort ?: 0} }
                      tls: { enabled: false }
                      authentication: { enabled: false }
                    outbox:
                      gateway:
                        outbox: { every: 1s, initial-jitter: 0s }
                      wait:
                        outbox: { every: 1s, initial-jitter: 0s }
                """.trimIndent()
                val body = listOfNotNull(namespace?.let { "namespaces: $it" }, configuration).joinToString("\n")
                Files.writeString(path, "lemline:\n" + body.prependIndent("  ") + "\n")
            }

        fun worker(label: String, namespace: String): Runner =
            launch(label, config(label, namespace), "listen", "--metrics-port", "0")

        fun startGateway(label: String): Gateway {
            val grpcPort = ServerSocket(0).use { it.localPort }
            val runner = launch(label, config(label, gatewayPort = grpcPort),
                "gateway", "start", "--grpc-port", grpcPort.toString(), "--metrics-port", "0")
            // The socket can accept connections while Quarkus is still starting its messaging channels.
            eventually("gateway startup completed", 45) {
                check(runner.process.isAlive) { "Gateway exited before startup:\n${runner.logText()}" }
                runner.logText().contains("Starting gRPC gateway server...")
            }
            return Gateway(runner, grpcPort)
        }

        private fun launch(label: String, config: Path, vararg args: String): Runner {
            val log = processLogs.resolve("$label.log")
            val command = PackagedEnvironment.artifactCommand + listOf("--config", config.toString(), "--info") + args
            val process = ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
            return Runner(process, log).also { processes += it }
        }

        fun eventually(description: String, seconds: Long = 20, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            do {
                if (condition()) return
                Thread.sleep(50)
            } while (System.nanoTime() < deadline)
            error("Timed out after ${seconds}s: $description. Process logs: ${processLogs.toAbsolutePath()}")
        }

        override fun close() {
            processes.asReversed().forEach { it.stop() }
        }
    }

    private class Gateway(val runner: Runner, private val port: Int) {
        fun startWorkflow(
            namespace: String,
            id: String = IDV7.random().toString(),
            expected: StartInstanceResult = StartInstanceResult.START_INSTANCE_RESULT_ACCEPTED_NEW,
        ): String {
            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
            try {
                val response = GatewayServiceGrpc.newBlockingStub(channel).withWaitForReady()
                    .withDeadlineAfter(20, TimeUnit.SECONDS).startInstance(StartInstanceRequest.newBuilder()
                        .setNamespace(namespace).setName("waiter").setInstanceId(id).build())
                assertEquals(id, response.instanceId)
                assertEquals(expected, response.result)
                return id
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    private class Runner(val process: Process, private val log: Path) {
        fun logText(): String = Files.readString(log)

        fun stop(kill: Boolean = false) {
            if (!process.isAlive) return
            if (kill) process.destroyForcibly() else process.destroy()
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Could not stop ${process.pid()}: $log" }
            }
        }
    }
}

private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }

private fun Connection.scalar(sql: String): String? = createStatement().use { statement ->
    statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
}
