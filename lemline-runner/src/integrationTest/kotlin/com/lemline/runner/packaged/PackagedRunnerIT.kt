// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.packaged

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.runner.common.test.RequiresDocker
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runs a workflow end to end with the packaged artifact: a runner (`lemline listen`) executes an
 * instance whose HTTP call reaches a local server, with a body expression and a bearer token read
 * from a secret. `lemline listen` never exits on its own, so this test starts and stops the process
 * itself instead of using `@QuarkusMainIntegrationTest`.
 */
@RequiresDocker
internal class PackagedRunnerIT {

    private data class Received(val body: String, val authorization: String?)

    @Test
    fun `a runner executes an HTTP workflow with evaluated body and secret`() {
        val received = CompletableFuture<Received>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/echo") { exchange ->
                val body = exchange.requestBody.readAllBytes().decodeToString()
                received.complete(Received(body, exchange.requestHeaders.getFirst("Authorization")))
                val response = """{"ok":true}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val name = "runner-${UUID.randomUUID()}"
        val definition = PackagedEnvironment.write(
            "$name.yaml",
            """
            |document:
            |  dsl: '1.0.3'
            |  namespace: packaged
            |  name: $name
            |  version: '1.0.0'
            |use:
            |  secrets:
            |    - PACKAGED_TOKEN
            |do:
            |  - post:
            |      call: http
            |      with:
            |        method: post
            |        endpoint:
            |          uri: http://127.0.0.1:${server.address.port}/echo
            |          authentication:
            |            bearer:
            |              token: ${'$'}{ ${'$'}secrets.PACKAGED_TOKEN }
            |        body:
            |          greeting: ${'$'}{ "Hello, " + .name }
            """.trimMargin(),
        )
        val runnerLog = PackagedEnvironment.workDir.resolve("$name-runner.log").toFile()
        var runner: Process? = null
        try {
            assertSucceeded(PackagedEnvironment.run("migrate", "--force"))
            assertSucceeded(PackagedEnvironment.run("definition", "post", "-f", definition.toString()))

            runner = ProcessBuilder(PackagedEnvironment.command("listen", "--metrics-port", "0", "--info"))
                .directory(PackagedEnvironment.workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(runnerLog)
                .apply { environment()["PACKAGED_TOKEN"] = "packaged-secret" }
                .start()

            assertSucceeded(PackagedEnvironment.run("instance", "start", "packaged", name, "--input", """{"name":"Lemline"}"""))

            val request = try {
                received.get(90, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw AssertionError("The runner did not call the HTTP endpoint:\n${runnerLog.readText()}", e)
            }
            assertEquals(mapOf("greeting" to "Hello, Lemline"), ObjectMapper().readValue(request.body, Map::class.java))
            assertEquals("Bearer packaged-secret", request.authorization)
        } finally {
            runner?.destroy()
            if (runner?.waitFor(20, TimeUnit.SECONDS) == false) runner.destroyForcibly()
            server.stop(0)
        }
    }

    private fun assertSucceeded(result: Pair<Int, String>) {
        val (exitCode, output) = result
        assertEquals(0, exitCode, output)
        assertTrue(!output.contains("ERROR processing"), output)
    }
}
