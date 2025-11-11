// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows.integration

import com.lemline.core.execution.complete.CompleteOrchestrator
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Migrated version of IfTest using the new CompleteOrchestrator implementation.
 * Tests conditional task execution with 'if' directive.
 */
@ExperimentalTime
class IfIntegrationTest {

    @Test
    fun `test without if`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                set:
                  in: ${ .in + 1 }
             - b:
                set:
                  in: ${ .in + 2 }
             - c:
                set:
                  in: ${ .in + 3}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(6, output["in"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test first if false`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                if: ${ .in > 0 }
                set:
                  in: ${ .in + 1 }
             - b:
                set:
                  in: ${ .in + 2 }
             - c:
                set:
                  in: ${ .in + 3}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(5, output["in"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test first if true`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                if: ${ .in == 0 }
                set:
                  in: ${ .in + 1 }
             - b:
                set:
                  in: ${ .in + 2 }
             - c:
                set:
                  in: ${ .in + 3}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(6, output["in"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test with subsequent if`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                if: ${ .in > 0 }
                set:
                  in: ${ .in + 1 }
             - b:
                if: ${ .in > 0 }
                set:
                  in: ${ .in + 2 }
             - c:
                set:
                  in: ${ .in + 3}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(3, output["in"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test with second if`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                set:
                  in: ${ .in + 1 }
             - b:
                if: ${ .in > 1 }
                set:
                  in: ${ .in + 2 }
             - c:
                set:
                  in: ${ .in + 3}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(4, output["in"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test with last if`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                set:
                  in: ${ .in + 1 }
             - b:
                set:
                  in: ${ .in + 2 }
             - c:
                if: ${ .in > 10 }
                set:
                  in: ${ .in + 3}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(3, output["in"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test nested without if`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                set:
                  in: ${ .in + 1 }
             - b:
                do:
                 - b1:
                    set:
                      in: ${ .in + 2 }
                 - b2:
                    set:
                      in: ${ .in + 3 }
                 - b3:
                    set:
                      in: ${ .in + 4}
             - c:
                set:
                  in: ${ .in + 5}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(15, output["in"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test nested with if`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                set:
                  in: ${ .in + 1 }
             - b:
                if: ${ .in > 1 }
                do:
                 - b1:
                    set:
                      in: ${ .in + 2 }
                 - b2:
                    set:
                      in: ${ .in + 3 }
                 - b3:
                    set:
                      in: ${ .in + 4}
             - c:
                set:
                  in: ${ .in + 5}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(6, output["in"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test nested with nested if`() = runTest {
        val doYaml = $$"""
           do:
             - a:
                set:
                  in: ${ .in + 1 }
             - b:
                do:
                 - b1:
                    if: ${ .in > 1 }
                    set:
                      in: ${ .in + 2 }
                 - b2:
                    set:
                      in: ${ .in + 3 }
                 - b3:
                    set:
                      in: ${ .in + 4}
             - c:
                set:
                  in: ${ .in + 5}
        """
        val rootNode = getWorkflowNode(doYaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(mapOf("in" to JsonPrimitive(0)))) as JsonObject

        assertEquals(13, output["in"]?.jsonPrimitive?.int)
    }
}
