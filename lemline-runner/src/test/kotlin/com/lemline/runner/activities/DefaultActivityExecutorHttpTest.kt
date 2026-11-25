// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.common.json.parseJson
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.errors.CommunicationException
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.random.random
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.messaging.InstanceMessageCodec
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultActivityExecutorHttpTest {
    @Test
    fun `a workflow sends expanded HTTP URI values encoded exactly once`() = runTest {
        val requestTarget = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val response = """{"matched":true}"""
        server.createContext("/") { exchange ->
            exchange.use {
                requestTarget.set(it.requestURI.toASCIIString())
                val bytes = response.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val result = FullOrchestrator.start(
                workflow = WorkflowCache.parseYamlAndPut(
                    """
                    document: {dsl: '1.0.3', namespace: test, name: http-uri-template, version: '1.0.0'}
                    do:
                      - getProduct:
                          call: http
                          with:
                            method: get
                            endpoint:
                              uri: http://127.0.0.1:${server.address.port}/products/{productId}/%7Bliteral%7D?source={source}
                    """.trimIndent(),
                ),
                workflowInput = parseJson("""{"productId":"SKU/1 café%20","source":"a+b"}"""),
                serde = true,
                activityExecutor = DefaultActivityExecutor(),
                functionResolver = MockFunctionResolver(),
                cloudEventHook = InMemoryCloudEventHook(),
                lifecycleHook = LifecycleEventHook.NOOP,
            )
            assertEquals(parseJson(response), result)
            assertEquals("/products/SKU%2F1%20caf%C3%A9%2520/%7Bliteral%7D?source=a%2Bb", requestTarget.get())
        } finally {
            server.stop(0)
        }
    }

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

    @Test
    fun `a workflow follows HTTP redirects only when it opts in`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val targetCalls = AtomicInteger()
        val targetBody = """{"stock":100}"""
        server.createContext("/old-stock") { exchange ->
            exchange.use {
                it.responseHeaders.add("Location", "/stock")
                it.sendResponseHeaders(301, -1)
            }
        }
        server.createContext("/stock") { exchange ->
            exchange.use {
                targetCalls.incrementAndGet()
                val bytes = targetBody.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val executor = DefaultActivityExecutor()
            suspend fun call(redirect: Boolean?) = FullOrchestrator.start(
                workflow = WorkflowCache.parseYamlAndPut(
                    """
                    document: {dsl: '1.0.3', namespace: test, name: http-redirect, version: '1.0.0'}
                    do:
                      - fetch:
                          call: http
                          with:
                            method: get
                            endpoint: http://127.0.0.1:${server.address.port}/old-stock
                    """.trimIndent() + if (redirect == null) "" else "\n        redirect: $redirect",
                ),
                serde = true,
                activityExecutor = executor,
                functionResolver = MockFunctionResolver(),
                cloudEventHook = InMemoryCloudEventHook(),
                lifecycleHook = LifecycleEventHook.NOOP,
            )

            val defaultFailure = assertFailsWith<InternalException> { call(redirect = null) }
            assertTrue(defaultFailure.error.type.endsWith(WorkflowErrorType.COMMUNICATION.type))
            assertEquals(301, defaultFailure.error.status)
            assertEquals(0, targetCalls.get())

            assertEquals(parseJson(targetBody), call(redirect = true))
            assertEquals(1, targetCalls.get())

            val explicitFailure = assertFailsWith<InternalException> { call(redirect = false) }
            assertEquals(defaultFailure.error.type, explicitFailure.error.type)
            assertEquals(301, explicitFailure.error.status)
            assertEquals(1, targetCalls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a non-2xx response fails with a communication error carrying the HTTP status`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/inventory") { exchange ->
            exchange.use {
                val bytes = """{"error":"stub 503"}""".toByteArray()
                it.sendResponseHeaders(503, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val failure = assertFailsWith<CommunicationException> {
                DefaultActivityExecutor().execute(httpCall("http://127.0.0.1:${server.address.port}/inventory"))
            }
            assertEquals(503, failure.status)
            assertEquals("""HTTP 503: {"error":"stub 503"}""", failure.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an unresolved host fails with a communication error naming the host`() = runTest {
        val failure = assertFailsWith<CommunicationException> {
            DefaultActivityExecutor().execute(httpCall("http://lemline-unknown-host.invalid/inventory?token=secret"))
        }
        assertEquals(500, failure.status)
        assertEquals("Unable to reach http://lemline-unknown-host.invalid: unresolved host", failure.message)
    }

    @Test
    fun `a refused connection fails with a communication error naming the host`() = runTest {
        // Bind then release a port so that nothing listens on it.
        val port = ServerSocket(0).use { it.localPort }
        val failure = assertFailsWith<CommunicationException> {
            DefaultActivityExecutor().execute(httpCall("http://127.0.0.1:$port/inventory"))
        }
        assertEquals(500, failure.status)
        assertTrue(failure.message!!.startsWith("Unable to reach http://127.0.0.1:$port: "), failure.message)
    }

    private fun httpCall(url: String) = WorkflowEvent.CallHttpStarted(
        nodeStack = NodeStack.random(), input = parseJson("{}"), config = CallHttpConfig("GET", url),
    )
}
