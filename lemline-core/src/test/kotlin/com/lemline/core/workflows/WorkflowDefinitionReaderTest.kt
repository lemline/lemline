// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.LemlineJson
import com.lemline.core.expressions.EvaluationMode
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkflowDefinitionReaderTest {
    private val nestedWorkflow = """
        document:
          dsl: '1.0.0'
          namespace: test
          name: lazy-schema
          version: '1.0.0'
        do:
          - outer:
              do:
                - inner:
                    set:
                      result: ok
    """.trimIndent()

    @Test
    fun `lazy validation preserves SDK parsing in YAML and JSON`() {
        for (format in WorkflowFormat.entries) {
            val input = encode(nestedWorkflow, format)
            val expected = WorkflowReader.validation().read(input, format)
            val actual = WorkflowDefinitionReader.read(input, format)
            assertEquals(
                format.mapper().valueToTree<JsonNode>(expected),
                format.mapper().valueToTree<JsonNode>(actual),
            )
        }
    }

    @Test
    fun `evaluate mode survives parsing and workflow serialization`() {
        val definition = nestedWorkflow + "\nevaluate:\n  language: jq\n  mode: loose"
        for (format in WorkflowFormat.entries) {
            val workflow = WorkflowDefinitionReader.read(encode(definition, format), format)
            assertEquals(EvaluationMode.LOOSE, (workflow as EvaluatedWorkflow).evaluationMode)
            assertEquals("loose", format.mapper().valueToTree<JsonNode>(workflow).get("evaluate").get("mode").textValue())
            val serialized = LemlineJson.encodeToElement(workflow)
            assertEquals("loose", serialized["evaluate"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertEquals(
                EvaluationMode.LOOSE,
                (WorkflowDefinitionReader.read(serialized.toString(), WorkflowFormat.JSON) as EvaluatedWorkflow).evaluationMode,
            )
            assertEquals(EvaluationMode.LOOSE, WorkflowParser.createRootNode(workflow).evaluationMode)
        }
    }

    @Test
    fun `evaluate rejects invalid configuration`() {
        for (evaluate in listOf("mode: unknown", "mode: 2", "language: python", "extra: value")) {
            val definition = nestedWorkflow + "\nevaluate:\n  $evaluate"
            assertThrows(IllegalArgumentException::class.java) {
                WorkflowDefinitionReader.read(definition, WorkflowFormat.YAML)
            }
        }
    }

    @Test
    fun `lazy validation preserves errors including nested and unknown properties`() {
        val invalid = listOf(
            nestedWorkflow.replace("dsl: '1.0.0'", "dsl: false"),
            nestedWorkflow.replace("name: lazy-schema", "unexpected: lazy-schema"),
            nestedWorkflow.replace("set:", "unknownTask:"),
            nestedWorkflow.replace("set:", "unexpected: true\n            set:"),
        )
        for (format in WorkflowFormat.entries) {
            for (yaml in invalid) {
                val input = encode(yaml, format)
                val expected = assertThrows(IllegalArgumentException::class.java) {
                    WorkflowReader.validation().read(input, format)
                }
                val actual = assertThrows(IllegalArgumentException::class.java) {
                    WorkflowDefinitionReader.read(input, format)
                }
                assertEquals(expected.message!!.lines().toSet(), actual.message!!.lines().toSet())
            }
        }
    }

    private fun encode(yaml: String, format: WorkflowFormat): String =
        if (format == WorkflowFormat.YAML) yaml else {
            WorkflowFormat.JSON.mapper().writeValueAsString(WorkflowFormat.YAML.mapper().readTree(yaml))
        }

    @Test
    fun `lazy schema supports concurrent validation`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val expected = WorkflowFormat.YAML.mapper().valueToTree<JsonNode>(
                WorkflowReader.noValidation().read(nestedWorkflow, WorkflowFormat.YAML),
            )
            val results = executor.invokeAll((1..32).map {
                Callable {
                    val workflow = WorkflowDefinitionReader.read(nestedWorkflow, WorkflowFormat.YAML)
                    WorkflowFormat.YAML.mapper().valueToTree<JsonNode>(workflow)
                }
            })
            results.forEach { assertEquals(expected, it.get()) }
        } finally {
            executor.shutdownNow()
        }
    }
}
