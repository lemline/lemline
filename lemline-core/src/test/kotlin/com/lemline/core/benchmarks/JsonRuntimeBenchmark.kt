// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.benchmarks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.expressions.JQExpression
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import java.lang.management.ManagementFactory
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/** Same workloads before/after migration; parsing/definition loading is outside the timed region. */
object JsonRuntimeBenchmark {
    @Volatile private var sink: Any? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            repeat(3) { fork ->
                check(ProcessBuilder(
                    "${System.getProperty("java.home")}/bin/java", "-Xms512m", "-Xmx512m", "-cp",
                    System.getProperty("java.class.path"), javaClass.name, fork.toString(),
                ).inheritIO().start().waitFor() == 0)
            }
            return
        }
        println("ENV java=${System.getProperty("java.version")} vm=${System.getProperty("java.vm.name")} os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
        (LoggerFactory.getILoggerFactory() as LoggerContext).loggerList.forEach { it.level = Level.OFF }
        val input = parseJson("""{"items":[${(1..100).joinToString(",") { "{\"id\":$it,\"price\":$it,\"label\":\"item $it\"}" }}]}""")
        val scope = ObjectNode(mapOf("factor" to jsonValue(2)))
        val expression = jsonValue("\${ .items | map(.price * \$factor) | add }")
        val nested = parseJson("""{"count":"${'$'}{ .items | length }","total":"${'$'}{ .items | map(.price * ${'$'}factor) | add }","first":"${'$'}{ .items[0].label }"}""")
        val workflow = WorkflowCache.parseYamlAndPut("""
            document:
              dsl: '1.0.0'
              namespace: benchmark
              name: json-runtime
              version: '1.0.0'
            do:
        """.trimIndent() + "\n" + (1..8).joinToString("\n") { "  - step$it:\n      set: '\${ . + {count: (.items | length)} }'" })
        val activities = object : ActivityExecutor {
            override suspend fun execute(event: WorkflowEvent.ActivityStarted): JsonNode = error("Unexpected activity")
        }
        runBlocking {
            val cases: List<Pair<String, suspend () -> Any>> = listOf(
                "jq-scalar-100-items" to { JQExpression.eval(input, expression, scope, false) },
                "jq-object-3-expressions" to { JQExpression.eval(input, nested, scope, false) },
                "workflow-8-sets" to {
                    FullOrchestrator.start(workflow = workflow, workflowInput = input, serde = false, activityExecutor = activities, functionResolver = MockFunctionResolver(), cloudEventHook = InMemoryCloudEventHook(), lifecycleHook = LifecycleEventHook.NOOP)
                },
                "workflow-8-sets-protobuf" to {
                    FullOrchestrator.start(workflow = workflow, workflowInput = input, serde = true, activityExecutor = activities, functionResolver = MockFunctionResolver(), cloudEventHook = InMemoryCloudEventHook(), lifecycleHook = LifecycleEventHook.NOOP)
                },
            )
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            for ((name, operation) in cases) {
                repeat(3) { sample(operation, 300_000_000L, bean) }
                repeat(5) { iteration ->
                    val (count, nanos, bytes) = sample(operation, 500_000_000L, bean)
                    println("BENCH {\"case\":\"$name\",\"fork\":${args[0]},\"iteration\":$iteration,\"ns_per_op\":${nanos.toDouble()/count},\"bytes_per_op\":${bytes.toDouble()/count},\"operations\":$count}")
                }
            }
        }
    }

    private suspend fun sample(operation: suspend () -> Any, duration: Long, bean: com.sun.management.ThreadMXBean): Triple<Long, Long, Long> {
        val thread = Thread.currentThread().id
        val allocated = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        var count = 0L
        do {
            repeat(8) { sink = operation(); count++ }
        } while (System.nanoTime() - start < duration)
        return Triple(count, System.nanoTime() - start, bean.getThreadAllocatedBytes(thread) - allocated)
    }
}
