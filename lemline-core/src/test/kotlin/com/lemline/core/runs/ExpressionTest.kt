// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.runs

import com.lemline.common.json.LemlineJson
import com.lemline.core.RuntimeDescriptor
import com.lemline.core.expressions.scopes.TaskDescriptor
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import com.lemline.core.getWorkflowProcessor
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

@ExperimentalTime
class ExpressionTest {

    @Test
    fun `check context (expr)`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    foo: 42
                  export:
                    as: "@{ {ctx: .} }"
              - second:
                  set:
                    number: @{ @context.ctx.foo }
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            LemlineJson.encodeToElement(mapOf("number" to 42)),
            instance.rootInstance.transformedOutput,
        )
    }

    @Test
    fun `check context (json)`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    foo: 42
                  export:
                    as: {ctx: .}
              - second:
                  set:
                    number: @{ @context.ctx.foo }
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            LemlineJson.encodeToElement(mapOf("number" to 42)),
            instance.rootInstance.transformedOutput,
        )
    }

    @Test
    fun `check context (yaml)`() = runTest {
        val doYaml = """
            do:
              - first:
                  set:
                    foo: 42
                  export:
                    as:
                      ctx: .
              - second:
                  set:
                    number: @{ @context.ctx.foo }
        """
        val instance = getWorkflowProcessor(doYaml, JsonObject(mapOf()))

        instance.run()

        assertEquals(
            LemlineJson.encodeToElement(mapOf("number" to 42)),
            instance.rootInstance.transformedOutput,
        )
    }

    @Test
    fun `check expression can access task descriptor`() = runTest {
        val taskName = "myTask"
        val doYaml = """
            do:
              - $taskName:
                  set:
                    taskFromScope: @{ @task }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(0))

        instance.run()

        val expected = TaskDescriptor(
            name = taskName,
            reference = "/do/0/$taskName",
            definition = JsonObject(mapOf()),
            input = JsonPrimitive(0),
            output = null,
            startedAt = null,
        )
        val actual = (instance.rootInstance.transformedOutput as JsonObject)["taskFromScope"] as JsonObject
        assertEquals(JsonPrimitive(expected.name), actual["name"])
        assertEquals(JsonPrimitive(expected.reference), actual["reference"])
        assertEquals(expected.input, actual["input"])
    }

    @Test
    fun `check expression can access workflow descriptor`() = runTest {
        val taskName = "myTask"
        val doYaml = """
            do:
              - $taskName:
                  set:
                    workflowFromScope: @{ @workflow }
        """
        val workflowProcessor = getWorkflowProcessor(doYaml, JsonPrimitive(0))

        workflowProcessor.run()

        val expected = WorkflowDescriptor(
            id = workflowProcessor.workflowInstance.workflowId.toString(),
            definition = JsonObject(mapOf()),
            input = JsonPrimitive(0),
            startedAt = JsonObject(mapOf()),
        )
        val actual = (workflowProcessor.rootInstance.transformedOutput as JsonObject)["workflowFromScope"] as JsonObject
        assertEquals(JsonPrimitive(expected.id), actual["id"])
        assertEquals(expected.input, actual["input"])
    }

    @Test
    fun `check expression can access runtime`() = runTest {
        val taskName = "myTask"
        val doYaml = """
            do:
              - $taskName:
                  set:
                    runtimeFromScope: @{ @runtime }
        """
        val instance = getWorkflowProcessor(doYaml, JsonPrimitive(0))

        instance.run()

        val actual = (instance.rootInstance.transformedOutput as JsonObject)["runtimeFromScope"] as JsonObject
        assertEquals(actual, LemlineJson.encodeToElement(RuntimeDescriptor))
    }
}
