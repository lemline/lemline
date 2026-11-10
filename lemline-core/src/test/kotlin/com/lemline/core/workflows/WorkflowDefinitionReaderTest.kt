// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
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
