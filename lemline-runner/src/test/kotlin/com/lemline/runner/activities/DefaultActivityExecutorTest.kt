// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.core.errors.InternalException
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.random.random
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent.CallHttpStarted
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import kotlinx.serialization.json.JsonObject

class DefaultActivityExecutorTest : FunSpec({
    test("HTTP failure keeps a short title and bounded response details") {
        val responseBody = "<!DOCTYPE html><title>Forbidden</title>" + "x".repeat(5000) + "END"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/denied") { exchange ->
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val nodeStack = NodeStack.random()
            val event = CallHttpStarted(
                nodeStack = nodeStack,
                input = JsonObject(emptyMap()),
                config = CallHttpConfig(method = "GET", url = "http://127.0.0.1:${server.address.port}/denied"),
            )

            val exception = shouldThrow<InternalException> {
                DefaultActivityExecutor().execute(event)
            }

            exception.error.status shouldBe 403
            exception.error.position shouldBe event.nodePosition.toString()
            exception.error.title shouldBe "HTTP 403 Forbidden"
            exception.error.details shouldContain "Response content type: text/html"
            exception.error.details shouldContain "<!DOCTYPE html>"
            exception.error.details shouldContain "[response body truncated]"
            exception.error.details shouldNotContain "END"
        } finally {
            server.stop(0)
        }
    }
})
