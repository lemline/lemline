// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.parseJson
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.processors.EmitConfig
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.states.WorkflowEvent
import io.cloudevents.core.builder.CloudEventBuilder
import java.io.File
import java.net.SocketException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class WorkflowAuthoringScenarioTest {
    private val order = """{"order":{"id":"order-17","totalAmount":73,"items":["book"]}}"""

    private class Services(val respond: suspend (CallHttpConfig) -> JsonNode) : ActivityExecutor {
        val calls = mutableListOf<CallHttpConfig>()
        val events = mutableListOf<EmitConfig>()

        override suspend fun execute(event: WorkflowEvent.ActivityStarted): JsonNode = when (event) {
            is WorkflowEvent.CallHttpStarted -> {
                calls.add(event.config)
                respond(event.config)
            }
            is WorkflowEvent.EmitStarted -> {
                events.add(event.config)
                event.input
            }
            else -> error("Unexpected activity: ${event.nodePosition}")
        }
    }

    private suspend fun runScenario(file: String, input: String, services: Services, incoming: InMemoryCloudEventHook = InMemoryCloudEventHook()) {
        val definition = File("../.claude/skills/serverless-workflow-writer/assets", file)
        val workflow = WorkflowCache.parseYamlAndPut(definition.readText())
        val workflowInput = parseJson(input)
        // The starter validates input before handing the instance to the orchestrator.
        workflow.input?.schema?.let { SchemaValidator.validate(workflowInput, it) }
        FullOrchestrator.start(
            workflow = workflow,
            workflowInput = workflowInput,
            activityExecutor = services,
            functionResolver = MockFunctionResolver(),
            cloudEventHook = incoming,
            lifecycleHook = LifecycleEventHook.NOOP,
        )
    }

    @Test
    fun `customer template sends the identifier as a query and rejects malformed values`() = runTest {
        val services = Services { parseJson("""{"lifetimeValue":"unknown"}""") }
        val failure = assertFailsWith<InternalException> {
            runScenario("templates/http-orchestration.yaml", """{"customerId":"north/west"}""", services)
        }
        val request = services.calls.single()
        assertEquals("GET", request.method)
        assertEquals("https://api.example.com/customers", request.url)
        assertEquals(mapOf("id" to "north/west"), request.query)
        assertEquals("Invalid customer data", failure.error.title)
    }

    @Test
    fun `batch template handles each item once and summarizes an empty or mixed batch`() = runTest {
        for (items in listOf("[]", """[{"id":"a","score":85},{"id":"b","score":45}]""")) {
            val services = Services { parseJson("""{"receipt":true}""") }
            runScenario("templates/batch-loop-switch.yaml", """{"items":$items}""", services)
            val routes = if (items == "[]") emptyList() else listOf("approve", "reject")
            assertEquals(routes, services.calls.map { it.url.substringAfterLast('/') })
            assertEquals(parseJson("""{"count":${routes.size}}"""), services.events.single().data)
        }
    }

    @Test
    fun `missing identifiers are rejected before any side effect`() = runTest {
        val services = Services { error("Unexpected call") }
        assertFailsWith<IllegalArgumentException> {
            runScenario("evals/workflow-01.yaml", """{"order":{"items":[],"totalAmount":73}}""", services)
        }
        assertFailsWith<IllegalArgumentException> { runScenario("evals/workflow-03.yaml", """[{}]""", services) }
        assertEquals(emptyList(), services.calls)
        assertEquals(emptyList(), services.events)
    }

    @Test
    fun `reserve stock then retry transient payment failure and confirm original order`() = runTest {
        var attempts = 0
        val services = Services { call ->
            if (call.url == "https://payments.example.com/charges" && ++attempts == 1) error("HTTP 503: busy")
            parseJson("""{"receipt":"received"}""")
        }
        runScenario("evals/workflow-01.yaml", order, services)
        val (reserve, charge, retry) = services.calls
        assertEquals(listOf("reservations", "charges", "charges"), services.calls.map { it.url.substringAfterLast('/') })
        assertEquals(listOf("POST", "POST", "POST"), services.calls.map { it.method })
        assertEquals(parseJson("""{"orderId":"order-17","items":["book"]}"""), reserve.body)
        assertEquals(parseJson("""{"orderId":"order-17","amount":73}"""), charge.body)
        assertEquals("order-17", charge.headers["Idempotency-Key"])
        assertEquals(charge.headers, retry.headers)
        assertEquals(charge.body, retry.body)
        assertEquals("com.example.order.confirmed.v1", services.events.single().type)
        assertEquals(parseJson("""{"orderId":"order-17","totalAmount":73}"""), services.events.single().data)
    }

    @Test
    fun `persistent payment failure exhausts its retry budget without confirming the order`() = runTest {
        val services = Services { call ->
            if (call.url.endsWith("/charges")) error("HTTP 503: busy")
            parseJson("{}")
        }
        val failure = assertFailsWith<InternalException> { runScenario("evals/workflow-01.yaml", order, services) }
        assertEquals("Payment failed after retries", failure.error.title)
        assertEquals(5, services.calls.count { it.url.endsWith("/charges") })
        assertEquals(emptyList(), services.events)
    }

    @Test
    fun `retry a reset connection but never retry a permanent payment refusal`() = runTest {
        var reserveAttempts = 0
        val services = Services { call ->
            if (call.url.endsWith("/charges")) error("HTTP 400: invalid payment")
            if (++reserveAttempts == 1) throw SocketException("Connection reset")
            parseJson("{}")
        }
        assertFailsWith<InternalException> { runScenario("evals/workflow-01.yaml", order, services) }
        assertEquals(listOf("reservations", "reservations", "charges"), services.calls.map { it.url.substringAfterLast('/') })
        assertEquals(emptyList(), services.events)
    }

    @Test
    fun `malformed loan scores fail before any application is routed`() = runTest {
        val services = Services { parseJson("""{"applications":[{"id":"a","score":750},{"id":"b","score":"599"}]}""") }
        assertFailsWith<InternalException> { runScenario("evals/workflow-02.yaml", "{}", services) }
        assertEquals(listOf("GET"), services.calls.map { it.method })
        assertEquals(emptyList(), services.events)
    }

    @Test
    fun `nightly batch sends each application to exactly one service and emits one count`() = runTest {
        val batch = """{"applications":[{"id":"a","score":750},{"id":"b","score":600},{"id":"c","score":599}]}"""
        val services = Services { call ->
            if (call.url == "https://risk.example.com/applications/pending") parseJson(batch) else parseJson("{}")
        }
        runScenario("evals/workflow-02.yaml", "{}", services)
        assertEquals(listOf("GET", "POST", "POST", "POST"), services.calls.map { it.method })
        assertEquals(listOf("approve" to "a", "review" to "b", "reject" to "c"), services.calls.drop(1).map {
            it.url.substringAfterLast('/') to it.body!!["applicationId"].asText()
        })
        assertEquals("com.example.lending.batch.completed.v1", services.events.single().type)
        assertEquals(parseJson("""{"count":3}"""), services.events.single().data)
    }

    @Test
    fun `acknowledged incident notifies chat and ignores another incident's escalation`() = runTest {
        val services = Services { parseJson("{}") }
        runIncident("ack", services)
        assertEquals(listOf("https://chat.example.com/incidents/notify"), services.calls.map { it.url })
    }

    @Test
    fun `escalation races both notifications and publishes after the first success`() = runTest {
        val pagerStarted = CompletableDeferred<Unit>()
        val releasePager = CompletableDeferred<Unit>()
        val services = Services { call ->
            when (URI(call.url).host) {
                "pager.example.com" -> {
                    pagerStarted.complete(Unit)
                    releasePager.await()
                    parseJson("{}")
                }
                "oncall.example.com" -> {
                    pagerStarted.await()
                    parseJson("{}")
                }
                else -> error("Unexpected notification ${call.url}")
            }
        }
        try {
            runIncident("escalate", services)
        } finally {
            releasePager.complete(Unit)
        }
        assertEquals(listOf("oncall.example.com", "pager.example.com"), services.calls.map { URI(it.url).host }.sorted())
    }

    @Test
    fun `failed chat or escalation still publishes the processed event before faulting`() = runTest {
        for (kind in listOf("ack", "escalate")) {
            val services = Services { error("HTTP 503: unavailable") }
            val failure = assertFailsWith<InternalException> { runIncident(kind, services) }
            assertEquals("Incident notification failed", failure.error.title)
            assertEquals("com.example.incident.processed.v1", services.events.single().type)
            assertEquals(parseJson("""{"incidentId":"ticket-9","status":"notification-failed"}"""), services.events.single().data)
        }
    }

    private suspend fun runIncident(decision: String, services: Services) {
        val incoming = InMemoryCloudEventHook()
        for ((kind, id) in listOf("escalate" to "unrelated", decision to "ticket-9")) {
            incoming.emit(CloudEventBuilder.v1().withId("$kind-$id").withSource(URI("https://alerts.example.com"))
                .withType("com.example.incident.$kind.v1")
                .withData("application/json", """{"incidentId":"$id"}""".toByteArray()).build())
        }
        runScenario("evals/workflow-03.yaml", """[{"incidentId":"ticket-9"}]""", services, incoming)
        assertEquals("com.example.incident.processed.v1", services.events.single().type)
        assertEquals("ticket-9", services.events.single().data!!["incidentId"].asText())
        services.calls.forEach { assertEquals("ticket-9", it.body!!["incidentId"].asText()) }
    }
}
