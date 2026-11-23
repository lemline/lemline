// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.packaged

import com.google.protobuf.Timestamp
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.gateway.v1.GatewayServiceGrpc
import com.lemline.gateway.v1.GetInstanceRequest
import com.lemline.gateway.v1.InstanceStatus
import com.lemline.gateway.v1.ListDefinitionStatsRequest
import com.lemline.gateway.v1.ListInstanceEventsRequest
import com.lemline.gateway.v1.ListInstancesRequest
import com.lemline.runner.common.test.RequiresDocker
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

@RequiresDocker
internal class PackagedSQLiteAnalyticsIT {

    @Test
    fun `PGMQ lifecycle ingestion feeds a gateway with its analytics consumer disabled`() {
        assumeTrue(PackagedEnvironment.artifactCommand.size > 1, "Packaged SQLite analytics is qualified on JVM")
        val directory = Files.createTempDirectory("lemline-packaged-sqlite-analytics-")
        println("SQLite analytics packaged process logs: $directory")
        val config = PackagedEnvironment.sqliteAnalyticsConfig(directory)
        val database = directory.resolve("analytics.db")
        val name = "sqlite-analytics-${UUID.randomUUID()}"
        val definition = directory.resolve("$name.yaml")
        Files.writeString(definition, """
            |document:
            |  dsl: '1.0.0'
            |  namespace: packaged
            |  name: $name
            |  version: '1.0.0'
            |do:
            |  - mark:
            |      set:
            |        executed: true
        """.trimMargin())

        for (args in listOf(arrayOf("migrate", "--force"), arrayOf("definition", "post", "-f", definition.toString()))) {
            val (code, output) = PackagedEnvironment.run(*args, configFile = config)
            assertEquals(0, code, output)
        }
        assertTrue(Files.notExists(database))

        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
        val client = GatewayServiceGrpc.newBlockingStub(channel)
        val list = ListInstancesRequest.newBuilder().setNamespace("packaged").setName(name).build()
        val gatewayArgs = arrayOf("gateway", "start", "--info", "--grpc-port", port.toString(), "--metrics-port", "0")
        try {
            val firstHistory = packagedProcess(config, "gateway-fresh.log", *gatewayArgs) { gateway ->
                assertTrue(gateway.await { client.withDeadlineAfter(2, TimeUnit.SECONDS).listInstances(list) }.instancesList.isEmpty())
                assertTrue(Files.isRegularFile(database))

                val instanceId = packagedProcess(config, "runner-ingestion.log", "listen", "--info", "--metrics-port", "0") { runner ->
                    val (code, output) = PackagedEnvironment.run("instance", "start", "packaged", name, configFile = config)
                    assertEquals(0, code, output)

                    runner.await {
                        client.withDeadlineAfter(2, TimeUnit.SECONDS).listInstances(list).instancesList
                            .singleOrNull()?.takeIf { it.status == InstanceStatus.INSTANCE_STATUS_COMPLETED }
                    }.instanceId
                }
                val history = client.withDeadlineAfter(5, TimeUnit.SECONDS).listInstanceEvents(
                    ListInstanceEventsRequest.newBuilder().setInstanceId(instanceId).build()
                ).eventsList
                val types = history.map { it.eventType }
                assertEquals(1, types.count { it == LifecycleEventType.WORKFLOW_CREATED.type })
                assertEquals(1, types.count { it == LifecycleEventType.WORKFLOW_STARTED.type })
                assertEquals(1, types.count { it == LifecycleEventType.WORKFLOW_COMPLETED.type })
                assertEquals(history.size, history.map { it.cursor }.distinct().size)
                assertTrue(history.all { it.cloudEventJson.isNotBlank() })
                instanceId to history
            }
            // Both packaged processes have closed all connections before the gateway reopens the same file.
            val (instanceId, history) = firstHistory
            packagedProcess(config, "gateway-reopened.log", *gatewayArgs) { gateway ->
                val request = GetInstanceRequest.newBuilder().setInstanceId(instanceId).build()
                val instance = gateway.await {
                    client.withDeadlineAfter(2, TimeUnit.SECONDS).getInstance(request).instance
                }
                assertEquals(InstanceStatus.INSTANCE_STATUS_COMPLETED, instance.status)
                val reloaded = client.withDeadlineAfter(5, TimeUnit.SECONDS).listInstanceEvents(
                    ListInstanceEventsRequest.newBuilder().setInstanceId(instanceId).build()
                )
                assertEquals(history, reloaded.eventsList)
                val stats = client.withDeadlineAfter(5, TimeUnit.SECONDS).listDefinitionStats(
                    ListDefinitionStatsRequest.newBuilder()
                        .setNamespace("packaged")
                        .setName(name)
                        .setTimeFrom(Timestamp.getDefaultInstance())
                        .build()
                ).statsList.single()
                assertEquals(1L, stats.startedCount)
                assertEquals(1L, stats.completedCount)
                assertEquals(0L, stats.runningCountAtTimeTo)
            }
        } finally {
            channel.shutdownNow()
            channel.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun <T> packagedProcess(config: Path, logName: String, vararg args: String, run: (LoggedProcess) -> T): T {
        val log = config.parent.resolve(logName).toFile()
        val process = ProcessBuilder(PackagedEnvironment.command(*args, configFile = config))
            .directory(config.parent.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        try {
            return run(LoggedProcess(process, log.toPath()))
        } finally {
            process.destroy()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Packaged process did not stop: $log" }
            }
        }
    }

    private class LoggedProcess(val process: Process, val log: Path) {
        fun <T : Any> await(read: () -> T?): T {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (process.isAlive && System.nanoTime() < deadline) {
                try {
                    read()?.let { return it }
                } catch (e: StatusRuntimeException) {
                    if (e.status.code !in setOf(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED)) throw e
                }
                Thread.sleep(100)
            }
            error("Packaged process failed or timed out. Full log at $log:\n${Files.readString(log)}")
        }
    }
}
