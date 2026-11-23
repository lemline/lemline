// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.packaged

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.common.test.RequiresDocker
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

@RequiresDocker
internal class PackagedSqliteMessagingIT {
    @Test
    fun `SQLite carries all four channel families with PostgreSQL persistence`() {
        val env = PackagedEnvironment
        assumeTrue(env.artifactCommand.first().endsWith("java"), "SQLite messaging is qualified on the JVM")
        val config = env.sqliteConfig
        val file = env.workDir.resolve("messaging.db")
        val listener = "sqlite-listener-${UUID.randomUUID()}"
        val emitter = "sqlite-emitter-${UUID.randomUUID()}"
        val type = "test.$emitter"
        val listenerFile = env.write("$listener.yaml", workflow(listener, """
            |do:
            |  - listenForPayload:
            |      listen:
            |        to:
            |          one:
            |            with:
            |              type: $type
        """.trimMargin()))
        val emitterFile = env.write("$emitter.yaml", workflow(emitter, """
            |do:
            |  - persistWait:
            |      wait: PT1S
            |  - sendPayload:
            |      emit:
            |        event:
            |          with:
            |            source: https://lemline.test/packaged
            |            type: $type
            |            data:
            |              value: packaged-sqlite
        """.trimMargin()))

        fun cli(vararg args: String) {
            val (exit, output) = env.run(*args, configFile = config)
            assertEquals(0, exit, output)
        }

        println("Packaged SQLite logs: ${env.workDir.toRealPath()}")
        cli("--help")
        cli("--version")
        cli("migrate", "--force")
        cli("definition", "post", "-f", listenerFile.toString())
        cli("definition", "post", "-f", emitterFile.toString())
        assertTrue(!Files.exists(file), "Commands with messaging disabled must not open the file")
        cli("instance", "start", "packaged", listener)

        DriverManager.getConnection("jdbc:sqlite:${file.toUri()}").use { sqlite ->
            val queues = sqlite.createStatement().use { stmt ->
                stmt.executeQuery("SELECT DISTINCT queue FROM lemline_messages").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
            }
            assertEquals(setOf("packaged-sqlite-packaged-commands", "lemline-lifecycle-events"), queues)
        }

        val runnerLog = env.workDir.resolve("sqlite-listen.log").toFile()
        val runner = ProcessBuilder(env.command("listen", "--info", configFile = config))
            .directory(env.workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(runnerLog)
            .start()
        try {
            env.postgresConnection().use { orchestration ->
                await(runner, runnerLog) {
                    orchestration.value("SELECT id::text FROM lemline.lemline_listeners WHERE workflow_name = ?", listener)
                }
                cli("instance", "start", "packaged", emitter)
                await(runner, runnerLog) {
                    orchestration.value(
                        "SELECT id::text FROM lemline.lemline_waits WHERE workflow_name = ? AND outbox_completed_at IS NOT NULL",
                        emitter,
                    )
                }
            }
            env.postgresConnection(analytics = true).use { analytics ->
                val completed = "SELECT payload::text FROM lemline.lemline_lifecycle_events WHERE " +
                    "lemline_workflow_namespace = 'packaged' AND type = '${LifecycleEventType.WORKFLOW_COMPLETED.type}' " +
                    "AND lemline_workflow_name = ?"
                await(runner, runnerLog) { analytics.value(completed, emitter) }
                val payload = await(runner, runnerLog) { analytics.value(completed, listener) }
                assertEquals("packaged-sqlite", ObjectMapper().readTree(payload).at("/data/output/0/value").asText())
            }
        } finally {
            runner.destroy()
            if (!runner.waitFor(20, TimeUnit.SECONDS)) {
                runner.destroyForcibly()
                assertTrue(runner.waitFor(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun workflow(name: String, body: String) = """
        |document:
        |  dsl: '1.0.3'
        |  namespace: packaged
        |  name: $name
        |  version: '1.0.0'
        |$body
    """.trimMargin()

    private fun Connection.value(sql: String, workflowName: String): String? =
        prepareStatement(sql).use {
            it.setString(1, workflowName)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun await(runner: Process, log: java.io.File, read: () -> String?): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (runner.isAlive && System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(100)
        }
        error("Expected workflow state was not reached. Runner log: $log\n${log.readText()}")
    }
}
