// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.common.json.parseJson
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.random.random
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessageCodec
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultActivityExecutorHttpTest {
    @Test
    fun `runner sends native JSON bodies for POST PUT and PATCH after durable resume`() = runTest {
        val request = AtomicReference<List<String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val response = """{"amount":1.234567890123456789,"empty":[],"missing":null}"""
        server.createContext("/body") { exchange ->
            exchange.use {
                request.set(listOf(it.requestMethod, it.requestHeaders.getFirst("Content-Type").orEmpty(), it.requestBody.bufferedReader().readText(), it.requestHeaders.getFirst("Accept").orEmpty()))
                val bytes = response.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val executor = DefaultActivityExecutor()
            val body = parseJson("""{"value":1.25,"null":null,"empty":[],"text":"é☕"}""")
            for (method in listOf("POST", "PUT", "PATCH", "GET")) {
                val event = WorkflowEvent.CallHttpStarted(
                    nodeStack = NodeStack.random(), input = body,
                    config = CallHttpConfig(method, "http://127.0.0.1:${server.address.port}/body", body = body),
                )
                val restored = InstanceMessageCodec.workflowStateFromDbJson(InstanceMessageCodec.workflowStateToDbJson(event)) as WorkflowEvent.CallHttpStarted
                assertEquals(parseJson(response), executor.execute(restored))
                val (receivedMethod, contentType, receivedBody, accept) = request.get()
                assertEquals(method, receivedMethod)
                assertTrue(accept.contains("application/json"))
                if (method == "GET") assertEquals("", receivedBody) else {
                    assertTrue(contentType.startsWith("application/json"))
                    assertEquals(body, parseJson(receivedBody))
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
