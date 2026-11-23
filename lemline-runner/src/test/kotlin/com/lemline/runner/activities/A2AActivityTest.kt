// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.common.values.info
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.messaging.InstanceMessageCodec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class A2AActivityTest {
    private val document = "document: {dsl: '1.0.3', namespace: test, name: a2a, version: '1.0.0'}\n"
    private val observe = """  - observe: {set: {observed: '${'$'}{ . }'}}"""
    private val runtime = "https://serverlessworkflow.io/spec/1.0.0/errors/runtime"
    private val executor = DefaultActivityExecutor()

    private class Agent(val v1: Boolean = false) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val calls = CopyOnWriteArrayList<JsonNode>()
        val cardRequests = CopyOnWriteArrayList<String>()
        val credentials = CopyOnWriteArrayList<String>()
        var security = ""
        var rpcUrl = "$base/rpc"
        var reply: (HttpExchange, JsonNode) -> Unit = { exchange, request ->
            exchange.respond("application/json", """{"jsonrpc":"2.0","id":${request["id"]},"result":{"reply":"ok"}}""")
        }

        init {
            for (path in listOf("/.well-known/agent-card.json", "/cards/report")) server.createContext(path) { exchange ->
                exchange.use {
                    cardRequests.add(it.requestURI.toString())
                    credentials.add(it.requestHeaders.getFirst("Authorization").orEmpty())
                    val endpoint = if (v1) """"supportedInterfaces":[{"url":"$rpcUrl","protocolBinding":"JSONRPC","protocolVersion":"1.0"}]"""
                        else """"url":"$rpcUrl","protocolVersion":"0.3.0""""
                    it.respond("application/json", """{
                        "name":"Reporter","version":"1.0.0","description":"Reports on sales",
                        "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],
                        "capabilities":{"streaming":true,"pushNotifications":true},"skills":[],
                        $endpoint$security
                    }""")
                }
            }
            server.createContext("/rpc") { exchange ->
                exchange.use {
                    val request = parseJson(it.requestBody.bufferedReader().readText())
                    calls.add(request)
                    credentials.add(it.requestHeaders.getFirst("Authorization").orEmpty())
                    reply(it, request)
                }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    private suspend fun run(yaml: String, input: JsonNode = buildJsonObject {}, secrets: ObjectNode = buildJsonObject {}) =
        FullOrchestrator.start(
            WorkflowCache.parseYamlAndPut(yaml), workflowInput = input, activityExecutor = executor,
            functionResolver = MockFunctionResolver(), cloudEventHook = InMemoryCloudEventHook(),
            lifecycleHook = LifecycleEventHook.NOOP, secretsForWorkflow = { secrets },
        )

    private fun workflow(method: String, server: String) = document + """
        do:
          - ask:
              call: a2a
              with:
                method: $method
                server: $server
                parameters: '${'$'}{ .params }'
    """.trimIndent() + "\n" + observe

    @Test
    fun `official report workflow sends defaults and passes the entire result to the next task`() = runBlocking {
        Agent().use { agent ->
            val example = File("../examples/workflows/a2a-example.yaml").readText()
                .replace("https://example.com", agent.base)
            val output = run(example.trimEnd() + "\n" + observe)
            assertEquals(parseJson("""{"observed":{"reply":"ok"}}"""), output)
            assertEquals(listOf("/.well-known/agent-card.json"), agent.cardRequests)
            val request = agent.calls.single()
            val id = UUID.fromString(request.at("/params/message/messageId").textValue())
            assertEquals(parseJson("""{"message":{"messageId":"$id","role":"user","parts":[{"kind":"text","text":"Generate the Q1 sales report."}]}}"""), request["params"])
        }
    }

    private data class Operation(val dsl: String, val wire: String, val params: String, val result: String)
    private val message = """{"message":{"messageId":"author","role":"agent","parts":[{"text":"hi","metadata":{"flags":[true,null]}}]},"metadata":{"n":1.25}}"""
    private val events = """[{"task":{"id":"t","status":{"state":"TASK_STATE_WORKING"}}},{"artifactUpdate":{"taskId":"t","artifact":{"artifactId":"a","parts":[{"text":"first"}]},"append":true,"lastChunk":false}},{"statusUpdate":{"taskId":"t","status":{"state":"TASK_STATE_COMPLETED"}}}]"""
    private val push = "tasks/pushNotificationConfig/"
    private val operations = listOf(
        Operation("message/send", "SendMessage", message, """{"message":{"messageId":"reply","parts":[{"text":"ok"}]}}"""),
        Operation("message/stream", "SendStreamingMessage", message, events),
        Operation("tasks/get", "GetTask", """{"id":"t","historyLength":2}""", """{"id":"t","history":[]}"""),
        Operation("tasks/list", "ListTasks", """{"contextId":"c","includeArtifacts":false,"pageSize":4}""", """{"tasks":[],"nextPageToken":"p","totalSize":9}"""),
        Operation("tasks/cancel", "CancelTask", """{"id":"t"}""", """{"id":"t","status":{"state":"TASK_STATE_CANCELED"}}"""),
        Operation("tasks/resubscribe", "SubscribeToTask", """{"id":"t"}""", events),
        Operation(push + "set", "CreateTaskPushNotificationConfig", """{"taskId":"t","id":"cfg","url":"https://example.com/push","token":"test"}""", """{"id":"cfg","taskId":"t"}"""),
        Operation(push + "get", "GetTaskPushNotificationConfig", """{"taskId":"t","id":"cfg"}""", """{"id":"cfg","token":"test"}"""),
        Operation(push + "list", "ListTaskPushNotificationConfigs", """{"taskId":"t","pageSize":3}""", """{"configs":[],"nextPageToken":"p"}"""),
        Operation(push + "delete", "DeleteTaskPushNotificationConfig", """{"taskId":"t","id":"cfg"}""", "{}"),
        Operation("agent/getAuthenticatedExtendedCard", "GetExtendedAgentCard", """{"tenant":"east"}""", """{"name":"Extended","capabilities":{"streaming":true}}"""),
    )

    @TestFactory
    fun `DSL operations use SDK wire names and preserve raw results`() = operations.map { op ->
        dynamicTest(op.dsl) {
            runBlocking {
                Agent(v1 = true).use { agent ->
                    val expected = parseJson(op.result)
                    agent.reply = { exchange, request ->
                        if (expected.isArray) exchange.sse(request, expected.toList())
                        else exchange.respond("application/json", envelope(request, expected))
                    }
                    val params = parseJson(op.params)
                    val output = run(workflow(op.dsl, agent.base), buildJsonObject { put("params", params) })
                    val call = agent.calls.single()
                    assertEquals(op.wire, call["method"].textValue())
                    val wireParams = parseJson(op.params.replace("\"role\":\"agent\"", "\"role\":\"ROLE_AGENT\""))
                    assertEquals(wireParams, call["params"])
                    assertEquals(expected, output["observed"])
                }
            }
        }
    }

    @Test
    fun `card wins before evaluating server and resolves authentication and nested parameters`() = runBlocking {
        Agent().use { agent ->
            agent.security = ""","security":[{"key":[]}],"securitySchemes":{"key":{"type":"http","scheme":"bearer"}}"""
            val yaml = document + """
                use:
                  secrets: [token]
                  extensions:
                    - markCalls:
                        extend: call
                        before:
                          - mark:
                              set: {extended: true}
                              export: {as: '${'$'}{ . }'}
                  authentications:
                    access:
                      bearer: {token: '${'$'}{ ${'$'}secrets.token }'}
                do:
                  - ask:
                      call: a2a
                      with:
                        method: tasks/get
                        agentCard:
                          name: report
                          endpoint:
                            uri: '${'$'}{ .card }'
                            authentication: {use: access}
                        server: '${'$'}{ error("unused server") }'
                        parameters:
                          id: '${'$'}{ .task }'
                          metadata: {items: [false, '${'$'}{ .number }', null], extended: '${'$'}{ ${'$'}context.extended }'}
            """.trimIndent() + "\n" + observe
            val input = parseJson("""{"task":"t","number":4,"card":"${agent.base}/cards/report?rev=2"}""")
            assertEquals("ok", run(yaml, input, buildJsonObject { put("token", "secret") }).at("/observed/reply").asText())
            assertEquals(listOf("/cards/report?rev=2"), agent.cardRequests)
            assertEquals(listOf("Bearer secret", "Bearer secret"), agent.credentials)
            assertEquals(parseJson("""{"id":"t","metadata":{"items":[false,4,null],"extended":true}}"""), agent.calls.single()["params"])
        }
    }

    @Test
    fun `unsatisfied card security fails before posting`() = runBlocking {
        Agent().use { agent ->
            agent.security = ""","security":[{"key":[]}],"securitySchemes":{"key":{"type":"apiKey","in":"header","name":"X-Key"}}"""
            val e = assertFailsWith<InternalException> {
                run(workflow("tasks/list", agent.base), parseJson("""{"params":{}}"""))
            }
            assertEquals(runtime, e.error.type)
            assertTrue(agent.calls.isEmpty())
        }
    }

    @Test
    fun `card credentials stay with their configured origin`() = runBlocking {
        Agent().use { publisher ->
            Agent().use { remote ->
                publisher.rpcUrl = remote.base + "/rpc"
                publisher.security = ""","security":[{"auth":[]}],"securitySchemes":{"auth":{"type":"http","scheme":"basic"}}"""
                val yaml = document + """
                    do:
                      - ask:
                          call: a2a
                          with:
                            method: tasks/list
                            agentCard:
                              endpoint:
                                uri: ${publisher.base}/cards/report
                                authentication: {basic: {username: reader, password: example}}
                """.trimIndent()
                val denied = assertFailsWith<InternalException> { run(yaml) }
                assertEquals(runtime, denied.error.type)
                assertTrue(remote.calls.isEmpty())
                publisher.security = ""
                run(yaml)
                assertTrue(publisher.credentials.all { it.startsWith("Basic ") })
                assertEquals(listOf(""), remote.credentials)
            }
        }
    }

    @TestFactory
    fun `disconnected streams never reach the consumer with a prefix`() = listOf("message/stream", "tasks/resubscribe").map { method ->
        dynamicTest(method) {
            runBlocking {
                Agent().use { agent ->
                    agent.reply = { exchange, request ->
                        exchange.sse(request, listOf(parseJson(events).first()), truncate = true)
                    }
                    val e = assertFailsWith<InternalException> {
                        run(workflow(method, agent.base), parseJson("""{"params":{"id":"t"}}"""))
                    }
                    assertEquals(runtime, e.error.type)
                    assertEquals("/do/0/ask", e.error.position)
                    assertEquals(listOf(method), agent.calls.map { it["method"].asText() })
                }
            }
        }
    }

    @Test
    fun `DSL retry repeats the whole stream after a clean but premature EOF`() = runBlocking {
        Agent().use { agent ->
            val complete = parseJson("""[{"kind":"status-update","status":{"state":"working"},"final":false},{"kind":"artifact-update","artifact":{"parts":[{"kind":"text","text":"answer"}]}},{"kind":"status-update","status":{"state":"completed"},"final":true}]""")
            agent.reply = { exchange, request ->
                val attempt = if (agent.calls.size == 1) listOf(parseJson("""{"kind":"status-update","status":{"state":"working"},"metadata":{"attempt":1},"final":false}"""))
                    else complete.toList()
                exchange.sse(request, attempt)
            }
            val yaml = document + """
                do:
                  - guarded:
                      try:
                        - ask:
                            call: a2a
                            with:
                              method: message/stream
                              server: '${agent.base}'
                              parameters: {message: {messageId: retry-1, parts: [{kind: text, text: hello}]}}
                      catch:
                        retry: {delay: {milliseconds: 1}, limit: {attempt: {count: 1}}}
            """.trimIndent() + "\n" + observe
            assertEquals(complete, run(yaml)["observed"])
            assertEquals(listOf("message/stream", "message/stream"), agent.calls.map { it["method"].asText() })
            assertTrue(agent.calls.all { it.at("/params/message/messageId").asText() == "retry-1" })
        }
    }

    @Test
    fun `file card uses the resource loader and ignores the server`(@TempDir directory: Path) = runBlocking {
        Agent().use { agent ->
            val card = directory.resolve("report.json").toFile()
            card.writeText("""{"url":"${agent.base}/rpc"}""")
            val yaml = workflow("tasks/list", "'${agent.base}'")
                .replace("server:", "agentCard: {endpoint: '${card.toURI()}'}\n        server:")
            assertEquals("ok", run(yaml, parseJson("""{"params":{}}""")).at("/observed/reply").asText())
            assertTrue(agent.cardRequests.isEmpty())
        }
    }

    @Test
    fun `invalid parameters and missing destination fail before IO`() = runBlocking {
        Agent().use { agent ->
            val yaml = workflow("tasks/list", agent.base)
            val wrongType = assertFailsWith<InternalException> { run(yaml, parseJson("""{"params":[]}""")) }
            assertContains(wrongType.error.title.orEmpty(), "must evaluate to an object")
            val noDestination = assertFailsWith<InternalException> {
                run(yaml.lines().filterNot { it.trimStart().startsWith("server:") }.joinToString("\n"))
            }
            assertContains(noDestination.error.title.orEmpty(), "requires agentCard or server")
            assertTrue(agent.cardRequests.isEmpty())
        }
    }

    @Test
    fun `DSL catch sees the specified error type remote details and call position`() = runBlocking {
        Agent().use { agent ->
            agent.reply = { e, req ->
                e.respond("application/json", """{"jsonrpc":"2.0","id":${req["id"]},"error":{"code":-32001,"message":"Unknown task","data":{"task":"missing"}}}""")
            }
            val output = run(document + """
                do:
                  - guarded:
                      try:
                        - ask:
                            call: a2a
                            with: {method: tasks/get, server: '${agent.base}', parameters: {id: missing}}
                      catch:
                        errors: {with: {type: '$runtime'}}
                        as: failure
                        do:
                          - handled: {set: {caught: '${'$'}{ ${'$'}failure }'}}
            """.trimIndent())
            assertEquals(runtime, output.at("/caught/type").textValue())
            assertEquals("/do/0/guarded/try/0/ask", output.at("/caught/instance").textValue())
            assertContains(output.at("/caught/details").asText(), "-32001")
            assertContains(output.at("/caught/details").asText(), "Unknown task")
            assertContains(output.at("/caught/details").asText(), "missing")
        }
    }

    @Test
    fun `redelivery rebuilds the same message and the next loop iteration gets a new id`() = runBlocking {
        Agent().use { agent ->
            val workflow = WorkflowCache.parseYamlAndPut(document + """
                do:
                  - twice:
                      for: {each: item, in: '${'$'}{ [0,1] }'}
                      do:
                        - send:
                            call: a2a
                            with:
                              method: message/send
                              server: {uri: '${agent.base}'}
                              parameters: {message: {parts: [{kind: text, text: report}]}}
            """.trimIndent() + "\n" + observe)
            var command: WorkflowCommand = StepByStepOrchestrator.initCmd()
            suspend fun step(command: WorkflowCommand) = StepByStepOrchestrator.runByActivity(
                workflow, command, workflow.info, LifecycleEventHook.NOOP,
            )
            val scheduled = assertIs<WorkflowEvent.TaskScheduled>(step(command))
            command = scheduled.resume()
            val first = assertIs<WorkflowEvent.CallA2AStarted>(step(command))
            executor.execute(first)
            val restored = InstanceMessageCodec.workflowStateFromDbJson(InstanceMessageCodec.workflowStateToDbJson(command))
            val replay = assertIs<WorkflowEvent.CallA2AStarted>(step(assertIs<WorkflowCommand>(restored)))
            val restoredEvent = InstanceMessageCodec.workflowStateFromDbJson(InstanceMessageCodec.workflowStateToDbJson(replay))
            val replayOutput = executor.execute(assertIs<WorkflowEvent.CallA2AStarted>(restoredEvent))
            val next = assertIs<WorkflowEvent.TaskScheduled>(step(replay.resumeCompleted(replayOutput)))
            val second = assertIs<WorkflowEvent.CallA2AStarted>(step(next.resume()))
            val delivered = second.resumeCompleted(executor.execute(second))
            val decoded = InstanceMessageCodec.workflowStateFromDbJson(InstanceMessageCodec.workflowStateToDbJson(delivered))
            val completed = assertIs<WorkflowEvent.WorkflowCompleted>(step(assertIs<WorkflowCommand>(decoded)))
            assertEquals(parseJson("""{"observed":{"reply":"ok"}}"""), completed.output)
            val ids = agent.calls.map { UUID.fromString(it.at("/params/message/messageId").textValue()) }
            assertEquals(ids[0], ids[1])
            assertNotEquals(ids[0], ids[2])
            assertEquals(first.config, replay.config)
            val mock = MockActivityExecutor(MockConfiguration(a2aResults = mapOf("message/send" to jsonValue("mock"))))
            assertEquals(jsonValue("mock"), mock.execute(first))
        }
    }

    companion object {
        private fun envelope(request: JsonNode, result: JsonNode) =
            """{"jsonrpc":"2.0","id":${request["id"]},"result":$result}"""

        private fun HttpExchange.sse(request: JsonNode, events: List<JsonNode>, truncate: Boolean = false) {
            val body = events.joinToString("") { ": keep-alive\ndata: ${envelope(request, it)}\n\n" }
            respond("text/event-stream", body, if (truncate) 100 else 0)
        }

        private fun HttpExchange.respond(type: String, body: String, missingBytes: Int = 0) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", type)
            sendResponseHeaders(200, bytes.size.toLong() + missingBytes)
            responseBody.write(bytes)
        }
    }
}
