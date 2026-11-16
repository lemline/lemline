// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.activities.mock.HttpMockMatcher
import com.lemline.core.activities.mock.HttpMockResponse
import com.lemline.core.activities.mock.HttpMockRule
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.testcases.impl.WorkflowDependency
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The same observable contract runs through FullOrchestrator and every runner transport. */
object ExtensionTaskTestCases {
    private fun String.dsl() = trimIndent().replace('§', '$')
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun http(url: String, output: String) = HttpMockRule(
        HttpMockMatcher(url = url), HttpMockResponse(body = json(output))
    )
    private val readyEvent = CloudEventBuilder.v1().withId("extension-ready")
        .withType("example.extension.ready").withSource(URI("https://tests.example.com"))
        .withData("application/json", "{\"value\":42}".toByteArray()).build()

    private fun trace(assertions: Trace.() -> Unit): (List<CloudEvent>) -> String? = { events ->
        runCatching {
            check(events.map { it.id }.distinct().size == events.size) { "Duplicate lifecycle IDs" }
            check(events.isNotEmpty()) { "Missing lifecycle events" }
            Trace(events).assertions()
        }.exceptionOrNull()?.message
    }

    private class Trace(val events: List<CloudEvent>) {
        fun data(event: CloudEvent): JsonObject = json(String(event.data!!.toBytes())).jsonObject
        fun matching(task: String, kind: String) = events.filter {
            it.type.endsWith(".task.$kind.v1") && data(it)["task"]?.jsonPrimitive?.content == task
        }
        fun count(task: String, kind: String, expected: Int) {
            val actual = matching(task, kind).size
            check(actual == expected) { "$task $kind: expected $expected, got $actual" }
        }
        fun owner(task: String, starts: Int = 1, faults: Int = 0) {
            count(task, "started", starts)
            count(task, "completed", 1)
            count(task, "faulted", faults)
        }
        fun closesAfter(owner: String, lastStep: String) {
            val end = matching(owner, "completed").single()
            val stepEnd = matching(lastStep, "completed").single()
            check(!end.time!!.isBefore(stepEnd.time)) { "$owner completed before $lastStep" }
        }
        fun allInvocationsClosed() {
            val started = events.filter { it.type.endsWith(".task.started.v1") }
                .groupingBy { data(it)["task"] }.eachCount()
            val ended = events.filter { it.type.endsWith(".task.completed.v1") || it.type.endsWith(".task.faulted.v1") }
                .groupingBy { data(it)["task"] }.eachCount()
            check(started == ended) { "Unclosed invocations: started=$started, ended=$ended" }
        }
    }

    val cases: List<WorkflowTestCase> = listOf(
        WorkflowTestCase(
            name = "extensions - frozen selection and nested block order preserve owner data",
            yaml = """
                use:
                  extensions:
                    - first:
                        extend: all
                        when: '§{ §task.name == "owner" }'
                        before:
                          - mark:
                              set: { ignored: true }
                              export: { as: '§{ {log: ["before1"], changed: true} }' }
                        after:
                          - mark:
                              set: { ignored: true }
                              export: { as: '§{ §context + {log: (§context.log + ["after1"])} }' }
                    - second:
                        extend: set
                        when: '§{ §task.name == "owner" and §context.changed == null }'
                        before:
                          - mark:
                              set: { ignored: true }
                              export: { as: '§{ §context + {log: (§context.log + ["before2"])} }' }
                        after:
                          - mark:
                              set: { ignored: true }
                              export: { as: '§{ §context + {log: (§context.log + ["after2"])} }' }
                do:
                  - owner:
                      set: { keep: '§{ .keep }' }
                      export: { as: '§{ §context + {log: (§context.log + ["body"])} }' }
                  - result: { set: { keep: '§{ .keep }', log: '§{ §context.log }' } }
            """.dsl(),
            input = json("""{"keep":"input"}"""),
            validate = expectOutput(json("""{"keep":"input","log":["before1","before2","body","after2","after1"]}""")),
            validateLifecycleEvents = trace {
                owner("/do/0/owner")
                closesAfter("/do/0/owner", "/do/0/owner/_ext/0/after/do/0/mark")
                check(events.none { Regex("/_ext/.*?/_ext/").containsMatchIn(data(it)["task"].toString()) })
            }
        ),
        WorkflowTestCase(
            name = "extensions - durable before wait precedes for collection initialization",
            yaml = """
                use:
                  extensions:
                    - prepare:
                        extend: for
                        when: '§{ §context.items == null }'
                        before:
                          - pause: { wait: { milliseconds: 10 } }
                          - chooseItems:
                              set: { items: [1, 2], owner: '§{ §task.reference }' }
                              export: { as: '§{ . }' }
                do:
                  - iterate:
                      for: { each: item, in: '§{ §context.items }' }
                      do:
                        - remember: { set: { seen: '§{ §item }', keep: '§{ .keep }', owner: '§{ §context.owner }' } }
            """.dsl(),
            input = json("""{"keep":"input"}"""),
            validate = expectOutput(json("""{"seen":2,"keep":"input","owner":"/do/0/iterate"}""")),
            validateLifecycleEvents = trace {
                owner("/do/0/iterate")
                count("/do/0/iterate/_ext/0/before/do/0/pause", "completed", 1)
                count("/do/0/iterate/do/0/remember", "completed", 2)
                allInvocationsClosed()
            }
        ),
        WorkflowTestCase(
            name = "extensions - short circuit skips initialization and prepares output once",
            yaml = """
                use:
                  extensions:
                    - simulate:
                        extend: for
                        before:
                          - pause: { wait: { milliseconds: 10 } }
                          - result: { set: { count: 1 }, then: exit }
                        after:
                          - forbidden: { raise: { error: { type: 'https://tests.example.com/forbidden', status: 500 } } }
                do:
                  - owner:
                      for: { each: item, in: '§{ error("body must not initialize") }' }
                      do: [{ forbidden: { set: { unexpected: true } } }]
                      output: { as: '§{ {count: (.count + 1)} }' }
                      export: { as: '§{ {exports: ((§context.exports // 0) + 1)} }' }
                  - result: { set: { count: '§{ .count }', exports: '§{ §context.exports }' } }
            """.dsl(),
            validate = expectOutput(json("""{"count":2,"exports":1}""")),
            validateLifecycleEvents = trace {
                owner("/do/0/owner")
                check(events.none { data(it)["task"].toString().contains("forbidden") })
            }
        ),
        WorkflowTestCase(
            name = "extensions - durable after wait retains prepared HTTP result and owner scope",
            yaml = """
                use:
                  extensions:
                    - inspect:
                        extend: call
                        after:
                          - pause: { wait: { milliseconds: 10 } }
                          - inspect:
                              set: { raw: '§{ §task.output.count }', input: '§{ §task.input.seed }', prepared: '§{ .count }' }
                              export: { as: '§{ §context + {observation: .} }' }
                do:
                  - owner:
                      call: http
                      with: { method: get, endpoint: 'https://tests.example.com/value' }
                      output: { as: '§{ {count: (.count + 1)} }' }
                      export: { as: '§{ {exports: ((§context.exports // 0) + 1)} }' }
                  - result: { set: { count: '§{ .count }', context: '§{ §context }' } }
            """.dsl(),
            input = json("""{"seed":7}"""),
            mockConfig = MockConfiguration(httpMocks = listOf(http("https://tests.example.com/value", """{"count":1}"""))),
            validate = expectOutput(json("""{"count":2,"context":{"exports":1,"observation":{"raw":1,"input":7,"prepared":2}}}""")),
            validateLifecycleEvents = trace {
                owner("/do/0/owner")
                closesAfter("/do/0/owner", "/do/0/owner/_ext/0/after/do/1/inspect")
                allInvocationsClosed()
            }
        ),
        WorkflowTestCase(
            name = "extensions - listen body receives broker CloudEvent before after block",
            yaml = """
                use:
                  extensions:
                    - inspect:
                        extend: listen
                        after:
                          - inspect:
                              set: { received: '§{ .[0].value }' }
                              export: { as: '§{ . }' }
                do:
                  - owner:
                      listen: { to: { one: { with: { type: example.extension.ready } } }, read: data }
                  - result: { set: { received: '§{ §context.received }' } }
            """.dsl(),
            cloudEvents = listOf(readyEvent),
            validate = expectOutput(json("""{"received":42}""")),
            validateLifecycleEvents = trace {
                owner("/do/0/owner")
                closesAfter("/do/0/owner", "/do/0/owner/_ext/0/after/do/0/inspect")
            }
        ),
        WorkflowTestCase(
            name = "extensions - parent resumes from child workflow before running after",
            yaml = """
                use:
                  extensions:
                    - inspect:
                        extend: run
                        after:
                          - inspect: { set: { child: '§{ .child }' }, export: { as: '§{ . }' } }
                do:
                  - owner:
                      run: { workflow: { namespace: test, name: extension-child, version: '0.1.0' } }
                  - result: { set: { child: '§{ .child }', observed: '§{ §context.child }' } }
            """.dsl(),
            dependencies = listOf(WorkflowDependency(name = "extension-child", yaml = """
                do:
                  - pause: { wait: { milliseconds: 10 } }
                  - child: { set: { child: 42 } }
            """.dsl())),
            validate = expectOutput(json("""{"child":42,"observed":42}""")),
            validateLifecycleEvents = trace {
                owner("/do/0/owner")
                closesAfter("/do/0/owner", "/do/0/owner/_ext/0/after/do/0/inspect")
            }
        )
    ) + listOf("before", "after").map { phase -> listenerInFunction(phase) } +
        listOf(false, true).map { local -> retry(local) } +
        listOf(false, true).map { compete -> fork(compete) } +
        listOf("before", "after").map { phase -> failure(phase) }

    private fun listenerInFunction(phase: String) = WorkflowTestCase(
        name = "extensions - $phase listener resolves mixed function and extension paths",
        yaml = """
            use:
              functions:
                main:
                  do:
                    - owner: { set: { answer: 42 } }
                audit:
                  do:
                    - receive:
                        listen: { to: { one: { with: { type: example.extension.ready } } }, read: data }
                    - remember:
                        set: { received: '§{ .[0].value }', owner: '§{ §task.reference }' }
                        export: { as: '§{ . }' }
              extensions:
                - observe:
                    extend: set
                    when: '§{ §task.name == "owner" }'
                    $phase:
                      - invoke: { call: audit }
            do:
              - invoke: { call: main }
              - result: { set: { answer: '§{ .answer }', received: '§{ §context.received }', owner: '§{ §context.owner }' } }
        """.dsl(),
        cloudEvents = listOf(readyEvent),
        rebuildCachesOnListen = true,
        validate = expectOutput(json("""{"answer":42,"received":42,"owner":"/do/0/invoke/_fn/do/0/owner"}""")),
        validateLifecycleEvents = trace {
            val owner = "/do/0/invoke/_fn/do/0/owner"
            owner(owner)
            count("$owner/_ext/0/$phase/do/0/invoke/_fn/do/0/receive", "completed", 1)
            allInvocationsClosed()
        }
    )

    private fun retry(local: Boolean): WorkflowTestCase {
        val audit = """
            - countAudit:
                set: { ignored: true }
                export: { as: '§{ §context + {audits: ((§context.audits // 0) + 1)} }' }
            - writeAudit:
                call: http
                with:
                  method: post
                  endpoint: https://tests.example.com/audit
                  query: { attempt: '§{ §context.audits | tostring }' }
        """.dsl()
        val after = if (!local) audit else """
            - auditRetry:
                try:
            AUDIT
                catch:
                  retry: { delay: { milliseconds: 10 }, limit: { attempt: { count: 1 } } }
        """.dsl().replace("AUDIT", audit.prependIndent("      "))
        val payments = if (local) 1 else 2
        val owner = "/do/0/guarded/try/0/charge"
        val auditTask = "$owner/_ext/0/after/do/" + if (local) "0/auditRetry/try/1/writeAudit" else "1/writeAudit"
        return WorkflowTestCase(
            name = "extensions - ${if (local) "local retry keeps payment" else "outer retry repeats payment"}",
            yaml = """
                use:
                  extensions:
                    - audit:
                        extend: call
                        when: '§{ §task.name == "charge" }'
                        after:
                AFTER
                do:
                  - guarded:
                      try:
                        - charge:
                            call: http
                            with: { method: post, endpoint: 'https://tests.example.com/payment' }
                            export: { as: '§{ §context + {payments: ((§context.payments // 0) + 1)} }' }
                      catch:
                        retry: { delay: { milliseconds: 10 }, limit: { attempt: { count: 1 } } }
                  - result: { set: { paymentId: '§{ .paymentId }', payments: '§{ §context.payments }', audits: '§{ §context.audits }' } }
            """.dsl().replace("AFTER", after.prependIndent("          ")),
            mockConfig = MockConfiguration(httpMocks = listOf(
                http("https://tests.example.com/payment", """{"paymentId":"paid"}"""),
                HttpMockRule(HttpMockMatcher(url = "https://tests.example.com/audit", query = mapOf("attempt" to "1")),
                    HttpMockResponse(error = "Audit temporarily unavailable")),
                HttpMockRule(HttpMockMatcher(url = "https://tests.example.com/audit", query = mapOf("attempt" to "2")),
                    HttpMockResponse(body = json("""{"logged":true}""")))
            )),
            validate = expectOutput(json("""{"paymentId":"paid","payments":$payments,"audits":2}""")),
            validateLifecycleEvents = trace {
                owner(owner, starts = payments, faults = if (local) 0 else 1)
                count(auditTask, "started", 2)
                count(auditTask, "faulted", 1)
                count(auditTask, "completed", 1)
                count("/do/0/guarded", "started", 1)
                val retryScope = if (local) "$owner/_ext/0/after/do/0/auditRetry/try" else "/do/0/guarded/try"
                count(retryScope, "retried", 1)
                closesAfter(owner, auditTask)
                allInvocationsClosed()
            }
        )
    }

    private fun fork(compete: Boolean) = WorkflowTestCase(
        name = "extensions - fork compete=$compete completes coordination before after",
        yaml = """
            use:
              extensions:
                - observe:
                    extend: composite
                    when: '§{ §task.name == "owner" }'
                    before:
                      - pause: { wait: { milliseconds: 10 } }
                    after:
                      - pause: { wait: { milliseconds: 10 } }
            do:
              - owner:
                  fork:
                    compete: $compete
                    branches:
                      - a: { set: { branch: a } }
                      - b: { set: { branch: b } }
              - result: { set: { complete: true } }
        """.dsl(),
        validate = expectOutput(json("""{"complete":true}""")),
        validateLifecycleEvents = trace {
            owner("/do/0/owner")
            closesAfter("/do/0/owner", "/do/0/owner/_ext/0/after/do/0/pause")
        }
    )

    private fun failure(phase: String): WorkflowTestCase {
        val owner = "/do/0/guarded/try/0/owner"
        val origin = "$owner/_ext/0/$phase/do/1/failure"
        return WorkflowTestCase(
            name = "extensions - suspended $phase failure closes owner and reaches outer catch",
            yaml = """
                use:
                  extensions:
                    - fail:
                        extend: call
                        $phase:
                          - pause: { wait: { milliseconds: 10 } }
                          - failure: { raise: { error: { type: 'https://tests.example.com/audit', status: 503 } } }
                do:
                  - guarded:
                      try:
                        - owner:
                            call: http
                            with: { method: post, endpoint: 'https://tests.example.com/payment' }
                      catch:
                        errors: { with: { type: 'https://tests.example.com/audit', status: 503, instance: '$origin' } }
                        do:
                          - recovered: { set: { origin: '§{ §error.instance }', status: '§{ §error.status }' } }
            """.dsl(),
            mockConfig = MockConfiguration(httpMocks = listOf(http("https://tests.example.com/payment", "{}"))),
            validate = expectOutput(json("""{"origin":"$origin","status":503}""")),
            validateLifecycleEvents = trace {
                count(owner, "started", 1)
                count(owner, "completed", 0)
                count(owner, "faulted", 1)
                count(origin, "faulted", 1)
                allInvocationsClosed()
            }
        )
    }
}
