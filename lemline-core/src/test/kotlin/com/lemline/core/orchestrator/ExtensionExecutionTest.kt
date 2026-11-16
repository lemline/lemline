// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.values.*
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.functions.FunctionCache
import com.lemline.core.lifecycleevents.*
import com.lemline.core.states.*
import com.lemline.core.states.protobuf.WorkflowStateProtobufMapper
import com.lemline.core.workflows.WorkflowCache
import com.lemline.messages.internal.v1.WorkflowStateProto
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.Workflow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ExtensionExecutionTest {
    private fun source(body: String) = """
        document:
          dsl: '1.0.0'
          namespace: extensions
          name: test
          version: '1.0.0'
    """.trimIndent() + "\n" + body.trimIndent().replace('§', '$')

    private fun parse(body: String) = WorkflowCache.parseYamlAndPut(source(body))
    private class Capture : LifecycleEventEmitter {
        val events = mutableListOf<CloudEvent>()
        override suspend fun emit(cloudEvent: CloudEvent) { events.add(cloudEvent) }
        val hook = CloudEventLifecycleHook(this)
        fun task(event: CloudEvent) = Json.parseToJsonElement(String(event.data!!.toBytes())).jsonObject["task"]?.jsonPrimitive?.content
        fun matching(position: String, type: String) = events.filter { task(it) == position && it.type.contains(".task.$type") }
    }
    private val transport = object : CloudEventHook {
        override suspend fun emit(event: CloudEvent) = Unit
        override fun receive() = emptyFlow<CloudEvent>()
    }
    private fun serde(state: WorkflowState): WorkflowState = WorkflowStateProtobufMapper.fromProto(
        WorkflowStateProto.ADAPTER.decode(WorkflowStateProtobufMapper.toProto(state).encode())
    )
    private suspend fun run(workflow: Workflow, capture: Capture = Capture(), execute: suspend (WorkflowEvent.ActivityStarted) -> JsonElement = { error("Unexpected activity $it") }): JsonElement =
        FullOrchestrator.start(workflow, serde = true, activityExecutor = object : ActivityExecutor {
            override suspend fun execute(event: WorkflowEvent.ActivityStarted) = execute(event)
        }, functionResolver = MockFunctionResolver(), cloudEventHook = transport, lifecycleHook = capture.hook)

    @Test
    fun `before suspension survives reconstructed caches before for initialization`() = runBlocking<Unit> {
        val body = """
            use:
              extensions:
                - prepare:
                    extend: for
                    when: '§{ §context.items == null }'
                    before:
                      - pause:
                          wait: { milliseconds: 1 }
                      - chooseItems:
                          set: { items: [1, 2], owner: '§{ §task.reference }' }
                          export:
                            as: '§{ . }'
            do:
              - iterate:
                  for: { each: item, in: '§{ §context.items }' }
                  do:
                    - remember:
                        set: { seen: '§{ §item }', keep: '§{ .keep }', owner: '§{ §context.owner }' }
        """
        var workflow = parse(body)
        val capture = Capture()
        var command: WorkflowCommand = StepByStepOrchestrator.initCmd(workflowInput = Json.parseToJsonElement("""{"keep":"input"}"""))
        var suspended = false
        repeat(100) {
            val event = serde(StepByStepOrchestrator.runByTask(workflow, serde(command) as WorkflowCommand, workflow.info, capture.hook)) as WorkflowEvent
            when (event) {
                is WorkflowEvent.TaskScheduled -> command = event.resume()
                is WorkflowEvent.WaitStarted -> {
                    suspended = true
                    val frame = event.nodeStack[NodePosition("/do/0/iterate")]!!
                    assertIs<PendingTaskState>(frame.state)
                    assertEquals(ExtensionPhase.BEFORE, frame.extension!!.phase)
                    assertEquals(listOf(0), frame.extension!!.selectedExtensionIndices)
                    WorkflowCache.clear(); FunctionCache.clear(); workflow = parse(body)
                    command = WorkflowCommand.ResumeWithCompletedTask(event.nodeStack, event.rawOutput)
                }
                is WorkflowEvent.WorkflowCompleted -> {
                    assertTrue(suspended)
                    assertEquals(Json.parseToJsonElement("""{"seen":2,"keep":"input","owner":"/do/0/iterate"}"""), event.output)
                    assertEquals(1, capture.matching("/do/0/iterate", "started").size)
                    assertEquals(1, capture.matching("/do/0/iterate", "completed").size)
                    assertEquals(2, capture.matching("/do/0/iterate/do/0/remember", "completed").size)
                    return@runBlocking
                }
                else -> error("Unexpected event: $event")
            }
        }
        fail("Did not complete")
    }

    @Test
    fun `short circuit skips body initialization and transforms and exports exactly once`() = runBlocking<Unit> {
        val workflow = parse("""
            use:
              extensions:
                - mock:
                    extend: for
                    before:
                      - result:
                          set: { count: 1 }
                          then: exit
                    after:
                      - forbidden:
                          raise:
                            error: { type: "https://example.com/error", status: 500 }
            do:
              - mocked:
                  for: { each: item, in: '§{ error("must not initialize") }' }
                  do:
                    - forbidden: { set: { unexpected: true } }
                  output: { as: '§{ {count: (.count + 1)} }' }
                  export: { as: '§{ {exports: ((§context.exports // 0) + 1), count: .count} }' }
              - check: { set: { exports: '§{ §context.exports }', count: '§{ §context.count }' } }
        """)
        assertEquals(Json.parseToJsonElement("""{"exports":1,"count":2}"""), run(workflow))
    }

    @Test
    fun `after failure retries locally or reexecutes payment under outer retry`() = runBlocking<Unit> {
        for (local in listOf(false, true)) {
            val audit = """
                - writeAudit:
                    call: http
                    with: { method: post, endpoint: 'https://example.com/audit' }
            """.trimIndent()
            val wrapped = """
                - auditRetry:
                    try:
                AUDIT_BLOCK
                    catch:
                      errors: { with: { status: 503 } }
                      retry: { delay: { milliseconds: 1 }, limit: { attempt: { count: 1 } } }
            """.trimIndent().replace("AUDIT_BLOCK", audit.prependIndent("      "))
            val after = if (local) wrapped else audit
            val workflow = parse("""
                use:
                  extensions:
                    - audit:
                        extend: call
                        when: '§{ §task.name == "charge" }'
                        after:
                AFTER_BLOCK
                do:
                  - guarded:
                      try:
                        - charge:
                            call: http
                            with: { method: post, endpoint: 'https://example.com/charge' }
                      catch:
                        errors: { with: { status: 503 } }
                        retry: { delay: { milliseconds: 1 }, limit: { attempt: { count: 1 } } }
            """.trimIndent().replace("AFTER_BLOCK", after.prependIndent("          ")))
            var payments = 0
            var audits = 0
            val capture = Capture()
            val result = run(workflow, capture) { event ->
                if (event.nodePosition.toString().endsWith("/charge")) {
                    payments++
                    Json.parseToJsonElement("""{"paymentId":"p-$payments"}""")
                } else {
                    audits++
                    if (audits == 1) throw InternalException(InternalException.Error("test", 503, event.nodePosition.toString(), "Audit unavailable"))
                    Json.parseToJsonElement("""{"logged":true}""")
                }
            }
            assertEquals(if (local) 1 else 2, payments)
            assertEquals(2, audits)
            assertEquals(Json.parseToJsonElement("""{"paymentId":"p-$payments"}"""), result)
            val owner = "/do/0/guarded/try/0/charge"
            assertEquals(1, capture.matching(owner, "completed").size)
            assertEquals(if (local) 0 else 1, capture.matching(owner, "faulted").size)
            assertEquals(1, capture.matching("/do/0/guarded", "started").size)
            assertEquals(capture.events.size, capture.events.map { it.id }.distinct().size)
        }
    }

    @Test
    fun `all wraps declarations without recursion and retains task data and exports in block order`() = runBlocking<Unit> {
        val workflow = parse("""
            use:
              extensions:
                - first:
                    extend: all
                    before:
                      - a:
                          set: { ignored: true }
                          export: { as: '§{ {log: ((§context.log // []) + ["before1"])} }' }
                    after:
                      - a:
                          set: { ignored: true }
                          export: { as: '§{ {log: (§context.log + ["after1"])} }' }
                - second:
                    extend: set
                    before:
                      - b:
                          set: { ignored: true }
                          export: { as: '§{ {log: (§context.log + ["before2"])} }' }
                    after:
                      - b:
                          set: { ignored: true }
                          export: { as: '§{ {log: (§context.log + ["after2"])} }' }
            do:
              - owner:
                  set: { answer: 42 }
                  export: { as: '§{ {log: (§context.log + ["body"])} }' }
        """)
        val capture = Capture()
        assertEquals(Json.parseToJsonElement("""{"answer":42}"""), run(workflow, capture))
        val started = capture.events.filter { it.type.contains(".task.started") }.mapNotNull(capture::task)
        assertEquals(listOf("/do", "/do/0/owner", "/do/0/owner/_ext/0/before/do", "/do/0/owner/_ext/0/before/do/0/a", "/do/0/owner/_ext/1/before/do", "/do/0/owner/_ext/1/before/do/0/b", "/do/0/owner/_ext/1/after/do", "/do/0/owner/_ext/1/after/do/0/b", "/do/0/owner/_ext/0/after/do", "/do/0/owner/_ext/0/after/do/0/a"), started)
    }
    @Test
    fun `selection errors fail owner before body and own try cannot catch them`() = runBlocking<Unit> {
        for (condition in listOf("42", "error(\"bad condition\")")) {
            val workflow = parse("""
                use:
                  extensions:
                    - invalid:
                        extend: try
                        when: '§{ $condition }'
                        before:
                          - forbidden: { set: { ran: true } }
                do:
                  - guarded:
                      try:
                        - body: { set: { ran: true } }
                      catch:
                        do:
                          - recovered: { set: { caught: true } }
            """)
            val capture = Capture()
            val failure = assertFailsWith<InternalException> { run(workflow, capture) }
            assertTrue(failure.error.type.endsWith("/expression"))
            assertTrue(failure.error.title!!.contains("invalid"))
            assertEquals(1, capture.matching("/do/0/guarded", "faulted").size)
            assertFalse(capture.events.any { capture.task(it)?.contains("/try/") == true })
        }
    }

    @Test
    fun `mixed function extension paths preserve owner scope and suppress recursive extensions`() = runBlocking<Unit> {
        val workflow = parse("""
            use:
              functions:
                main:
                  do:
                    - child: { set: { value: 42 } }
                observe:
                  do:
                    - verify:
                        set: { owner: '§{ §task.reference }' }
                        export: { as: '§{ {owner: .owner} }' }
              extensions:
                - around:
                    extend: set
                    before:
                      - invoke: { call: observe }
            do:
              - invoke: { call: main }
              - result:
                  set: { owner: '§{ §context.owner }' }
                  if: '§{ false }'
        """)
        val capture = Capture()
        assertEquals(Json.parseToJsonElement("""{"value":42}"""), run(workflow, capture))
        val extensionStep = "/do/0/invoke/_fn/do/0/child/_ext/0/before/do/0/invoke/_fn/do/0/verify"
        assertEquals(1, capture.matching(extensionStep, "completed").size)
        val data = Json.parseToJsonElement(String(capture.matching(extensionStep, "completed").single().data!!.toBytes())).jsonObject
        assertEquals("/do/0/invoke/_fn/do/0/child", data["output"]!!.jsonObject["owner"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extension return rejects mismatched block and continuation presence is mandatory`() = runBlocking<Unit> {
        val workflow = parse("""
            use:
              extensions:
                - prepare:
                    extend: set
                    before:
                      - pause: { wait: { milliseconds: 1 } }
            do:
              - owner: { set: { done: true } }
        """)
        val capture = Capture()
        val start = StepByStepOrchestrator.initCmd()
        val first = StepByStepOrchestrator.runByTask(workflow, start, workflow.info, capture.hook) as WorkflowEvent.TaskScheduled
        val pending = StepByStepOrchestrator.runByTask(workflow, first.resume(), workflow.info, capture.hook) as WorkflowEvent.TaskScheduled
        val owner = pending.nodeStack.currentPosition
        val invalid = WorkflowCommand.ResumeFromTask(pending.nodeStack, owner, JsonNull, extensionReturn = ExtensionReturn(NodePosition("/wrong"), ExtensionReturnKind.COMPLETE))
        assertIs<WorkflowEvent.WorkflowFaulted>(StepByStepOrchestrator.runByTask(workflow, invalid, workflow.info, capture.hook))
        assertFailsWith<IllegalArgumentException> { serde(invalid.copy(nodeStack = pending.nodeStack.withExtension(null))) }
    }

    @Test
    fun `end in before terminates workflow while nested exit stays local`() = runBlocking<Unit> {
        for (directive in listOf("end", "exit")) {
            val workflow = parse("""
                use:
                  extensions:
                    - controls:
                        extend: set
                        when: '§{ §task.name == "owner" }'
                        before:
                          - nested:
                              do:
                                - result: { set: { stopped: true }, then: $directive }
                          - finish: { set: { ignored: true } }
                do:
                  - owner: { set: { body: true } }
                  - last: { set: { last: true } }
            """)
            assertEquals(Json.parseToJsonElement(if (directive == "end") """{"stopped":true}""" else """{"last":true}"""), run(workflow))
        }
    }

    @Test
    fun `fork extensions surround coordination in both modes without fictitious branches`() = runBlocking<Unit> {
        for (compete in listOf(false, true)) {
            val workflow = parse("""
                use:
                  extensions:
                    - around:
                        extend: composite
                        before:
                          - prepare: { set: { ignored: true } }
                        after:
                          - finish: { set: { ignored: true } }
                do:
                  - forked:
                      fork:
                        compete: $compete
                        branches:
                          - a: { set: { result: 1 } }
                          - b: { set: { result: 2 } }
            """)
            val capture = Capture()
            run(workflow, capture)
            assertEquals(1, capture.matching("/do/0/forked", "completed").size)
            assertEquals(1, capture.matching("/do/0/forked/_ext/0/after/do/0/finish", "completed").size)
        }
    }

    @Test
    fun `after suspension keeps prepared result raw task output and exported context`() = runBlocking<Unit> {
        val body = """
            use:
              extensions:
                - observe:
                    extend: call
                    after:
                      - pause: { wait: { milliseconds: 1 } }
                      - inspect:
                          set: { raw: '§{ §task.output.count }', input: '§{ §task.input.seed }', output: '§{ .count }' }
                          export: { as: '§{ §context + {observation: .} }' }
            do:
              - owner:
                  call: http
                  with: { method: get, endpoint: 'https://example.com/value' }
                  output: { as: '§{ {count: (.count + 1)} }' }
                  export: { as: '§{ {exports: ((§context.exports // 0) + 1)} }' }
              - report:
                  set: { count: '§{ .count }', context: '§{ §context }' }
        """
        var workflow = parse(body)
        val capture = Capture()
        var calls = 0
        var command: WorkflowCommand = StepByStepOrchestrator.initCmd(workflowInput = Json.parseToJsonElement("""{"seed":7}"""))
        repeat(100) {
            when (val event = serde(StepByStepOrchestrator.runByTask(workflow, serde(command) as WorkflowCommand, workflow.info, capture.hook)) as WorkflowEvent) {
                is WorkflowEvent.TaskScheduled -> command = event.resume()
                is WorkflowEvent.CallHttpStarted -> {
                    calls++
                    command = WorkflowCommand.ResumeWithCompletedTask(event.nodeStack, Json.parseToJsonElement("""{"count":1}"""))
                }
                is WorkflowEvent.WaitStarted -> {
                    assertEquals(0, capture.matching("/do/0/owner", "completed").size)
                    assertEquals(ExtensionPhase.AFTER, event.nodeStack[NodePosition("/do/0/owner")]!!.extension!!.phase)
                    WorkflowCache.clear(); FunctionCache.clear(); workflow = parse(body)
                    command = WorkflowCommand.ResumeWithCompletedTask(event.nodeStack, event.rawOutput)
                }
                is WorkflowEvent.WorkflowCompleted -> {
                    assertEquals(1, calls)
                    assertEquals(Json.parseToJsonElement("""{"count":2,"context":{"exports":1,"observation":{"raw":1,"input":7,"output":2}}}"""), event.output)
                    assertEquals(1, capture.matching("/do/0/owner", "started").size)
                    assertEquals(1, capture.matching("/do/0/owner", "completed").size)
                    return@runBlocking
                }
                else -> error("Unexpected $event")
            }
        }
        fail("Did not complete")
    }

    @Test
    fun `selection is frozen before exports and after exit retains owner result and jump`() = runBlocking<Unit> {
        val workflow = parse("""
            use:
              extensions:
                - first:
                    extend: set
                    when: '§{ §task.name == "owner" }'
                    before:
                      - change: { set: { ignored: true }, export: { as: '§{ {changed: true} }' } }
                    after:
                      - forbidden: { raise: { error: { type: 'https://example.com/error', status: 500 } } }
                - selected:
                    extend: set
                    when: '§{ §task.name == "owner" and §context.changed == null }'
                    after:
                      - stop: { set: { ignored: true }, then: exit }
            do:
              - owner: { set: { value: 42 }, then: done }
              - forbidden: { raise: { error: { type: 'https://example.com/error', status: 500 } } }
              - done: { set: { value: '§{ .value }', changed: '§{ §context.changed }' } }
        """)
        assertEquals(Json.parseToJsonElement("""{"value":42,"changed":true}"""), run(workflow))
    }

    @Test
    fun `shared remote function keeps definition specific extensions after cache replacement`() = runBlocking<Unit> {
        val function = parse("do: [{ answer: { set: { value: 42 } } }]").`do`.single().task
        val resolver = MockFunctionResolver(com.lemline.core.activities.mock.MockConfiguration(functionDefinitions = mapOf("https://example.com/shared.yaml" to function)))
        for (marker in listOf("first", "second", "third")) {
            val workflow = parse("""
                use:
                  extensions:
                    - record:
                        extend: set
                        after:
                          - mark: { set: { ignored: true }, export: { as: '§{ {marker: "$marker"} }' } }
                do:
                  - invoke: { call: 'https://example.com/shared.yaml' }
                  - result: { set: { marker: '§{ §context.marker }' } }
            """)
            val output = FullOrchestrator.start(workflow, serde = true, activityExecutor = object : ActivityExecutor {
                override suspend fun execute(event: WorkflowEvent.ActivityStarted): JsonElement = error("Unexpected activity")
            }, functionResolver = resolver, cloudEventHook = transport, lifecycleHook = LifecycleEventHook.NOOP)
            assertEquals(Json.parseToJsonElement("""{"marker":"$marker"}"""), output)
        }
    }

    @Test
    fun `invalid extension is rejected at loading even without matching tasks`() {
        assertFails { parse("""
            use:
              extensions:
                - invalid:
                    extend: unknown
                    before: [{ anything: { set: { ignored: true } } }]
            do: [{ owner: { set: { ignored: true } } }]
        """) }
    }

    @Test
    fun `wait listen and subworkflow after blocks run only after asynchronous completion`() = runBlocking<Unit> {
        val bodies = listOf(
            "wait: { milliseconds: 1 }",
            "listen: { to: { one: { with: { type: example.ready } } } }",
            "run: { workflow: { namespace: extensions, name: child, version: '1.0.0' } }",
        )
        for (body in bodies) {
            WorkflowCache.parseYamlAndPut(source("do: [{ childResult: { set: { child: true } } }]").replace("name: test", "name: child"))
            val workflow = parse("""
                use:
                  extensions:
                    - observe:
                        extend: all
                        after:
                          - recorded: { set: { value: '§{ . }' } }
                do:
                  - owner:
                      $body
            """)
            val hook = object : CloudEventHook {
                override suspend fun emit(event: CloudEvent) = Unit
                override fun receive() = kotlinx.coroutines.flow.flowOf(io.cloudevents.core.builder.CloudEventBuilder.v1()
                    .withId("ready").withType("example.ready").withSource(java.net.URI("https://tests"))
                    .withData("application/json", "{\"value\":42}".toByteArray()).build())
            }
            val capture = Capture()
            FullOrchestrator.start(workflow, serde = true, activityExecutor = object : ActivityExecutor {
                override suspend fun execute(event: WorkflowEvent.ActivityStarted): JsonElement = error("Unexpected activity")
            }, functionResolver = MockFunctionResolver(), cloudEventHook = hook, lifecycleHook = capture.hook)
            val positions = capture.events.filter { it.type.contains(".task.completed") }.mapNotNull(capture::task)
            assertTrue(positions.indexOf("/do/0/owner/_ext/0/after/do/0/recorded") < positions.indexOf("/do/0/owner"))
            assertEquals(1, capture.matching("/do/0/owner", "completed").size)
        }
    }

    @Test
    fun `before and after failures after suspension preserve origin and close owner for outer catch`() = runBlocking<Unit> {
        for (phase in listOf("before", "after")) {
            val owner = "/do/0/guarded/try/0/owner"
            val origin = "$owner/_ext/0/$phase/do/1/failure"
            val workflow = parse("""
                use:
                  extensions:
                    - failing:
                        extend: call
                        $phase:
                          - pause: { wait: { milliseconds: 1 } }
                          - failure: { raise: { error: { type: 'https://example.com/audit', status: 503 } } }
                do:
                  - guarded:
                      try:
                        - owner:
                            call: http
                            with: { method: post, endpoint: 'https://example.com/payment' }
                      catch:
                        errors: { with: { type: 'https://example.com/audit', status: 503, instance: '$origin' } }
                        do:
                          - recovered: { set: { origin: '§{ §error.instance }', status: '§{ §error.status }' } }
            """)
            var payments = 0
            val capture = Capture()
            val result = run(workflow, capture) { payments++; JsonPrimitive("paid") }
            assertEquals(if (phase == "before") 0 else 1, payments)
            assertEquals(origin, result.jsonObject["origin"]!!.jsonPrimitive.content)
            assertEquals(503, result.jsonObject["status"]!!.jsonPrimitive.int)
            assertEquals(1, capture.matching(owner, "faulted").size)
            assertEquals(0, capture.matching(owner, "completed").size)
            assertEquals(1, capture.matching(origin, "faulted").size)
            assertEquals(capture.events.size, capture.events.map { it.id }.distinct().size)
        }
    }

    @Test
    fun `try cannot catch failure of its own after but outer try can`() = runBlocking<Unit> {
        val workflow = parse("""
            use:
              extensions:
                - failing:
                    extend: try
                    when: '§{ §task.name == "inner" }'
                    after:
                      - failure: { raise: { error: { type: 'https://example.com/audit', status: 503 } } }
            do:
              - outer:
                  try:
                    - inner:
                        try:
                          - body: { set: { done: true } }
                        catch:
                          do:
                            - local: { set: { caught: inner } }
                  catch:
                    do:
                      - recovered: { set: { caught: outer } }
        """)
        assertEquals(Json.parseToJsonElement("""{"caught":"outer"}"""), run(workflow))
    }

    @Test
    fun `remote resolution errors inside extension are handled locally without failing owner`() = runBlocking<Unit> {
        val workflow = parse("""
            use:
              extensions:
                - optionalAudit:
                    extend: set
                    after:
                      - guarded:
                          try:
                            - unavailable: { call: 'https://example.com/missing.yaml' }
                          catch:
                            do:
                              - recovered: { set: { ignored: true } }
            do:
              - owner: { set: { value: 42 } }
        """)
        val capture = Capture()
        assertEquals(Json.parseToJsonElement("""{"value":42}"""), run(workflow, capture))
        assertEquals(0, capture.matching("/do/0/owner", "faulted").size)
        assertEquals(1, capture.matching("/do/0/owner", "completed").size)
        assertEquals(1, capture.matching("/do/0/owner/_ext/0/after/do/0/guarded/try/0/unavailable", "faulted").size)
    }

    @Test
    fun `fork extension failures reach the outer catch rather than creating branch outcomes`() = runBlocking<Unit> {
        for (phase in listOf("before", "after")) for (compete in listOf(false, true)) {
            val workflow = parse("""
                use:
                  extensions:
                    - failing:
                        extend: composite
                        when: '§{ §task.name == "forked" }'
                        $phase:
                          - failure: { raise: { error: { type: 'https://example.com/audit', status: 503 } } }
                do:
                  - guarded:
                      try:
                        - forked:
                            fork:
                              compete: $compete
                              branches:
                                - a: { set: { branch: a } }
                                - b: { set: { branch: b } }
                      catch:
                        do:
                          - recovered: { set: { caught: true } }
            """)
            val capture = Capture()
            assertEquals(Json.parseToJsonElement("""{"caught":true}"""), run(workflow, capture))
            val owner = "/do/0/guarded/try/0/forked"
            assertEquals(1, capture.matching(owner, "faulted").size)
            assertEquals(0, capture.matching(owner, "completed").size)
            if (phase == "before") assertFalse(capture.events.any { capture.task(it)?.contains("/branches/") == true })
        }
    }

    @Test
    fun `legacy active frame is not retroactively extended`() = runBlocking<Unit> {
        val workflow = parse("""
            use:
              extensions:
                - forbidden:
                    extend: call
                    before:
                      - failure: { raise: { error: { type: 'https://example.com/error', status: 500 } } }
                    after:
                      - failure: { raise: { error: { type: 'https://example.com/error', status: 500 } } }
            do:
              - owner: { call: http, with: { method: get, endpoint: 'https://example.com/value' } }
        """)
        val capture = Capture()
        val scheduled = StepByStepOrchestrator.runByTask(workflow, StepByStepOrchestrator.initCmd(), workflow.info, capture.hook) as WorkflowEvent.TaskScheduled
        val legacy = scheduled.nodeStack.push(scheduled.nodePosition, TaskState(kotlin.time.Clock.System.now()))
        val result = FullOrchestrator.resume(workflow,
            WorkflowCommand.ResumeWithCompletedTask(legacy, JsonPrimitive("legacy")), serde = true,
            activityExecutor = com.lemline.core.activities.mock.MockActivityExecutor.empty(),
            functionResolver = MockFunctionResolver(), cloudEventHook = transport, lifecycleHook = capture.hook)
        assertEquals(JsonPrimitive("legacy"), result.value())
        assertFalse(capture.events.any { capture.task(it)?.contains("/_ext/") == true })
    }

}
