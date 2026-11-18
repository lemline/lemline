// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.info
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.processors.HttpAuthentication
import com.lemline.core.states.WorkflowEvent
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.types.Task
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SdkCompatibilityTest {
    private fun source(body: String) = """
        document:
          dsl: '1.0.0'
          namespace: sdk
          name: compatibility
          version: '1.0.0'
    """.trimIndent() + "\n" + body.trimIndent().replace('§', '$')

    private suspend fun run(
        body: String,
        input: JsonNode = ObjectNode(emptyMap()),
        serde: Boolean = true,
        execute: suspend (WorkflowEvent.ActivityStarted) -> JsonNode = { error("Unexpected activity: $it") },
    ): JsonNode = FullOrchestrator.start(
        workflow = WorkflowCache.parseYamlAndPut(source(body)),
        workflowInput = input,
        serde = serde,
        activityExecutor = object : ActivityExecutor {
            override suspend fun execute(event: WorkflowEvent.ActivityStarted) = execute(event)
        },
        functionResolver = MockFunctionResolver(),
        cloudEventHook = InMemoryCloudEventHook(),
        lifecycleHook = LifecycleEventHook.NOOP,
    )

    @Test
    fun `workflow input schemas retain precise decimal constants`() = runTest {
        val body = """
            input:
              schema:
                format: json
                document:
                  const: { amount: 1.234567890123456789 }
            do:
              - preserve:
                  set: '§{ . }'
        """
        val input = parseJson("""{"amount":1.234567890123456789}""")
        val workflow = WorkflowDefinitionReader.read(source(body), WorkflowFormat.YAML)
        com.lemline.core.schemas.SchemaValidator.validate(input, workflow.input.schema)
        assertEquals(input, run(body, input, serde = false))
    }

    @Test
    fun `set expression and inline for collection execute through serialized resumes`() = runTest {
        val output = run("""
            do:
              - collect:
                  for: { each: item, in: [1, 1, 2] }
                  do:
                    - append:
                        set: '§{ . + [§item] }'
        """, parseJson("[]"))
        assertEquals(parseJson("[1,1,2]"), output)
    }

    @Test
    fun `inline for collections evaluate nested expressions against the initial input`() = runTest {
        val output = run("""
            do:
              - collect:
                  for:
                    each: item
                    in: [literal, null, '§{ .customer }', { nested: '§{ .customer }', missing: null, values: [null] }]
                  do:
                    - append:
                        set: '§{ .result += [§item] | .customer = "changed" }'
        """, parseJson("""{"customer":"Alice","result":[]}"""))
        assertEquals(parseJson("""{"customer":"changed","result":["literal",null,"Alice",{"nested":"Alice","missing":null,"values":[null]}]}"""), output)
    }

    @Test
    fun `HTTP maps support whole object expressions and per value expressions`() = runTest {
        for (wholeObject in listOf(false, true)) {
            val headers = if (wholeObject) "'§{ .headers }'" else "{ X-Token: '§{ .token }', X-Literal: literal }"
            val query = if (wholeObject) "'§{ .query }'" else "{ q: '§{ .search }', page: '2' }"
            run("""
                do:
                  - request:
                      call: http
                      with:
                        method: GET
                        endpoint: https://example.com/search
                        headers: $headers
                        query: $query
            """, parseJson("""{"token":"secret","search":"hello world","headers":{"X-Token":"secret","X-Literal":"literal"},"query":{"q":"hello world","page":"2"}}""")) {
                val config = assertIs<WorkflowEvent.CallHttpStarted>(it).config
                assertEquals(mapOf("X-Token" to "secret", "X-Literal" to "literal"), config.headers)
                assertEquals(mapOf("q" to "hello world", "page" to "2"), config.query)
                jsonValue("ok")
            }
        }
    }

    @Test
    fun `HTTP body evaluates nested and whole body expressions`() = runTest {
        val input = parseJson("""{"name":"Alice","items":[1,2]}""")
        val bodies = mapOf(
            "{ user: { name: '§{ .name }' }, items: '§{ .items }', literal: plain, missing: null }" to
                parseJson("""{"user":{"name":"Alice"},"items":[1,2],"literal":"plain","missing":null}"""),
            "'§{ { user: .name, count: (.items | length) } }'" to
                parseJson("""{"user":"Alice","count":2}"""),
        )
        for ((body, expected) in bodies) {
            run("""
                do:
                  - request:
                      call: http
                      with:
                        method: post
                        endpoint: https://example.com/users
                        body: $body
            """, input) {
                assertEquals(expected, assertIs<WorkflowEvent.CallHttpStarted>(it).config.body)
                jsonValue("ok")
            }
        }
    }

    @Test
    fun `HTTP authentication properties evaluate expressions`() = runTest {
        val input = parseJson("""{"user":"alice","password":"pa55","token":"tok"}""")
        val cases = listOf(
            "" to "authentication: { basic: { username: '§{ .user }', password: '§{ .password }' } }" to
                HttpAuthentication.Basic("alice", "pa55"),
            "" to "authentication: { bearer: { token: '§{ .token }' } }" to
                HttpAuthentication.Bearer("tok"),
            "use: { authentications: { api: { bearer: { token: '§{ .token }' } } } }" to
                "authentication: { use: api }" to HttpAuthentication.Bearer("tok"),
            "" to "authentication: { bearer: { token: literal-token } }" to
                HttpAuthentication.Bearer("literal-token"),
        )
        for ((yaml, expected) in cases) {
            val (use, authentication) = yaml
            run("""
                $use
                do:
                  - request:
                      call: http
                      with:
                        method: get
                        endpoint:
                          uri: https://example.com/data
                          $authentication
            """, input) {
                assertEquals(expected, assertIs<WorkflowEvent.CallHttpStarted>(it).config.authentication)
                jsonValue("ok")
            }
        }
    }

    @Test
    fun `shell arguments and stdin survive evaluation and serialized resumes`() = runTest {
        run("""
            do:
              - shell:
                  run:
                    shell:
                      command: cat
                      arguments: ['§{ .arg }', '', '§{ .arg }', 'two words']
                      stdin: '§{ .text }'
        """, parseJson("""{"arg":"repeated","text":"standard input"}""")) {
            val config = assertIs<WorkflowEvent.RunShellStarted>(it).config
            assertEquals(listOf("repeated", "", "repeated", "two words"), config.arguments)
            assertEquals("standard input", config.stdin)
            jsonValue("ok")
        }
    }

    @Test
    fun `raised expressions match the SDK detail error filter`() = runTest {
        val output = run("""
            do:
              - attempt:
                  try:
                    - fail:
                        raise:
                          error:
                            type: https://example.com/error
                            status: 400
                            title: '§{ .title }'
                            detail: '§{ .detail }'
                  catch:
                    errors:
                      with:
                        title: invalid
                        detail: explanation
                    do:
                      - recovered:
                          set: { caught: true }
        """, parseJson("""{"title":"invalid","detail":"explanation"}"""))
        assertEquals(parseJson("""{"caught":true}"""), output)
    }

    @Test
    fun `catch transitions end or jump after an error without affecting successful tries`() = runTest {
        for (fails in listOf(false, true)) {
            for (target in listOf("end", "finish")) {
                val action = if (fails) "raise: { error: { type: https://example.com/error, status: 400 } }" else "set: { success: true }"
                val body = """
                    do:
                      - attempt:
                          try:
                            - action:
                                $action
                          catch:
                            then: $target
                      - skipped:
                          set: { intermediate: true }
                      - finish:
                          set: '§{ . + {finished: true} }'
                """
                val parsed = WorkflowDefinitionReader.read(source(body), WorkflowFormat.YAML)
                for (mapper in listOf(LemlineJson.jacksonMapper, LemlineJson.yamlMapper)) {
                    WorkflowDefinitionReader.read(mapper.writeValueAsString(parsed), WorkflowFormat.YAML)
                }
                val output = run(body, parseJson("""{"original":true}"""))
                val expected = when {
                    !fails -> """{"intermediate":true,"finished":true}"""
                    target == "end" -> """{"original":true}"""
                    else -> """{"original":true,"finished":true}"""
                }
                assertEquals(parseJson(expected), output)
            }
        }
    }

    @Test
    fun `wait and retry durations evaluate expressions in the runtime input`() = runTest {
        val output = run("""
            do:
              - pause:
                  wait: '§{ .delay }'
              - attempt:
                  try:
                    - fail:
                        raise:
                          error: { type: https://example.com/error, status: 400 }
                  catch:
                    retry:
                      delay: '§{ if §error.status == 400 then §input.delay else "PT1H" end }'
                      limit:
                        duration: '§{ §input.limit }'
                        attempt:
                          count: 1
                          duration: '§{ §input.limit }'
                      jitter:
                        from: '§{ §input.delay }'
                        to: '§{ §input.jitter }'
                    do:
                      - handled:
                          set: { caught: true }
        """, parseJson("""{"delay":"PT0S","limit":"PT1M","jitter":"PT0.001S"}"""))
        assertEquals(parseJson("""{"caught":true}"""), output)
    }

    @Test
    fun `listen timeout expressions use runtime input and reject invalid results`() = runTest {
        val workflow = WorkflowCache.parseYamlAndPut(source("""
            do:
              - receive:
                  listen:
                    to: { one: { with: { type: example } } }
                  timeout: { after: '§{ .delay }' }
        """))
        for (delay in listOf("PT1M", "invalid")) {
            val before = Clock.System.now()
            var event = StepByStepOrchestrator.runByTask(
                workflow, StepByStepOrchestrator.initCmd(workflowInput = parseJson("""{"delay":"$delay"}""")),
                workflow.info, LifecycleEventHook.NOOP,
            )
            while (event is WorkflowEvent.TaskScheduled) {
                event = StepByStepOrchestrator.runByTask(workflow, event.resume(), workflow.info, LifecycleEventHook.NOOP)
            }
            if (delay == "invalid") assertIs<WorkflowEvent.WorkflowFaulted>(event)
            else {
                val timeout = assertNotNull(assertIs<WorkflowEvent.ListenStarted>(event).config.timeoutAt)
                assertTrue(timeout in before + 1.minutes..Clock.System.now() + 1.minutes)
            }
        }
    }

    @Test
    fun `timer schedules reject runtime durations before registration`() {
        for (property in listOf("every", "after")) {
            val error = assertThrows<IllegalArgumentException> {
                WorkflowDefinitionReader.read(source("do:\n  - value:\n      set: { ok: true }\nschedule:\n  $property: '§{ .delay }'"), WorkflowFormat.YAML)
            }
            assertTrue(error.message!!.contains("schedule.$property must be a static duration"))
        }
    }

    @Test
    fun `multiline inline and reusable errors survive parsing serialization and execution`() = runTest {
        val error = """
            type: https://example.com/error
            status: 400
            title: |-
              first title line
              second title line
            detail: |-
              first detail line
              second detail line
        """.trimIndent()
        for (reusable in listOf(false, true)) {
            val body = "use:\n  errors:\n    failure:\n${error.prependIndent("      ")}\n" + """
                do:
                  - attempt:
                      try:
                        - fail:
                            raise:
                              error:
            """.trimIndent() + (if (reusable) " failure\n" else "\n${error.prependIndent("                  ")}\n") + """
                      catch:
                        do:
                          - recovered:
                              set: '§{ {title: §error.title, detail: §error.details} }'
            """.trimIndent().prependIndent("      ")
            val workflow = WorkflowDefinitionReader.read(source(body), WorkflowFormat.YAML)
            for ((format, mapper) in listOf(WorkflowFormat.JSON to LemlineJson.jacksonMapper, WorkflowFormat.YAML to LemlineJson.yamlMapper)) {
                val restored = WorkflowDefinitionReader.read(mapper.writeValueAsString(workflow), format)
                val definition = restored.use.errors.additionalProperties.getValue("failure")
                assertEquals("first title line\nsecond title line", definition.title.literalErrorTitle)
                assertEquals("first detail line\nsecond detail line", definition.detail.literalErrorDetails)
            }
            assertEquals(parseJson("""{"title":"first title line\nsecond title line","detail":"first detail line\nsecond detail line"}"""), run(body))
        }
    }

    @Test
    fun `workflow JSON and YAML round trips preserve null data and wildcard listeners`() {
        val workflow = WorkflowDefinitionReader.read(source("""
            do:
              - data:
                  set: { missing: null, empty: [] }
              - wildcard:
                  listen:
                    to: { any: [] }
        """), WorkflowFormat.YAML)
        for (mapper in listOf(LemlineJson.jacksonMapper, LemlineJson.yamlMapper)) {
            val text = mapper.writeValueAsString(workflow)
            val restored = WorkflowDefinitionReader.read(text, WorkflowFormat.YAML)
            assertEquals(LemlineJson.encodeToElement(workflow), LemlineJson.encodeToElement(restored))
            val data = restored.`do`[0].task.setTask.set.setTaskConfiguration.additionalProperties
            assertEquals(true, data.containsKey("missing"))
            assertEquals(null, data["missing"])
            assertEquals(emptyList<Any>(), data["empty"])
            assertEquals(emptyList<Any>(), restored.`do`[1].task.listenTask.listen.to.anyEventConsumptionStrategy.any)
        }
    }

    @Test
    fun `timer and event schedules remain valid after JSON and YAML serialization`() {
        val schedules = listOf(
            "every: PT1M", "after: PT1M", "cron: '0 * * * *'",
            "on: { one: { with: { type: example } } }\n  read: data",
            "on: { one: { with: { type: example } } }\n  read: envelope",
            "on: { one: { with: { type: example } } }\n  read: raw",
        )
        for (schedule in schedules) {
            val workflow = WorkflowDefinitionReader.read(
                source("do:\n  - data:\n      set: { value: ok }\nschedule:\n  $schedule"), WorkflowFormat.YAML,
            )
            for (mapper in listOf(LemlineJson.jacksonMapper, LemlineJson.yamlMapper)) {
                val text = mapper.writeValueAsString(workflow)
                val restored = WorkflowDefinitionReader.read(text, WorkflowFormat.YAML)
                assertEquals(workflow.schedule.read, restored.schedule.read)
                assertEquals(LemlineJson.encodeToElement(workflow), LemlineJson.encodeToElement(restored))
            }
        }
    }

    @Test
    fun `external function task JSON uses SDK union serialization`() {
        val tree = LemlineJson.yamlMapper.readTree("set: { answer: 42 }")
        val task = LemlineJson.jacksonMapper.treeToValue(tree, Task::class.java)
        assertEquals(tree, LemlineJson.jacksonMapper.valueToTree(task))
    }

    @Test
    fun `external named function calls deserialize to the correct SDK union`() {
        for (mapper in listOf(LemlineJson.jacksonMapper, LemlineJson.yamlMapper)) {
            val task = mapper.readValue("""{"call":"customFunction","with":{"value":42}}""", Task::class.java)
            assertEquals("customFunction", task.callTask.callFunction.call)
        }
    }
}
