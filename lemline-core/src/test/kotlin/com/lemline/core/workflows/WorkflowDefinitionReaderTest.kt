// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.json.content
import com.lemline.common.json.jsonObject
import com.lemline.common.json.jsonPrimitive
import com.lemline.core.expressions.EvaluationMode
import com.lemline.core.schemas.SchemaValidator
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkflowDefinitionReaderTest {
    private val nestedWorkflow = """
        document:
          dsl: '1.0.3'
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
    fun `packaged SDK schema matches the documented DSL version`() {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("schema/workflow.yaml"))
        val schema = resource.use { SchemaValidator.readSchema(it, WorkflowFormat.YAML) }
        assertEquals("https://open-workflow-specification.org/schemas/1.0.3/workflow.yaml", schema["\$id"].asText())
    }

    @Test
    fun `stored 1_0_0 and current definitions retain their declared DSL and independent workflow version`() {
        for (dsl in listOf("1.0.0", "1.0.3")) {
            val yaml = nestedWorkflow.replace("dsl: '1.0.3'", "dsl: '$dsl'")
            for (format in WorkflowFormat.entries) {
                val workflow = WorkflowDefinitionReader.read(encode(yaml, format), format)
                assertEquals(dsl, workflow.document.dsl)
                assertEquals("1.0.0", workflow.document.version)
            }
        }
    }

    @Test
    fun `script validation follows the pinned SDK for both formats`() {
        val script = """
            document: { dsl: '1.0.3', namespace: test, name: script, version: '0.1.0' }
            do:
              - greet:
                  run:
                    script:
                      language: js
                      code: console.log(process.argv)
                      arguments: { --name: Ada }
        """.trimIndent()
        for (format in WorkflowFormat.entries) {
            WorkflowDefinitionReader.read(encode(script, format), format)
            for (field in listOf("arguments: [--name, Ada]", "stdin: hello")) {
                val unsupported = script.replace("arguments: { --name: Ada }", field)
                assertThrows(IllegalArgumentException::class.java) {
                    WorkflowDefinitionReader.read(encode(unsupported, format), format)
                }
            }
        }
    }

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
            assertEquals(EvaluationMode.LOOSE, workflow.evaluationMode)
            assertEquals("loose", format.mapper().valueToTree<JsonNode>(workflow).get("evaluate").get("mode").textValue())
            val serialized = LemlineJson.encodeToElement(workflow)
            assertEquals("loose", serialized["evaluate"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertEquals(
                EvaluationMode.LOOSE,
                WorkflowDefinitionReader.read(serialized.toString(), WorkflowFormat.JSON).evaluationMode,
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
    fun `event read mode requires schedule on using the literal property name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WorkflowDefinitionReader.read(nestedWorkflow + "\nschedule:\n  read: envelope", WorkflowFormat.YAML)
        }
        org.junit.jupiter.api.Assertions.assertTrue(error.message!!.contains("'on'"))
    }

    @Test
    fun `lazy validation preserves errors including nested and unknown properties`() {
        val invalid = listOf(
            nestedWorkflow.replace("dsl: '1.0.3'", "dsl: false"),
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
