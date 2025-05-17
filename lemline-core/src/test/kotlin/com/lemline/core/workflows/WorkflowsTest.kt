// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.Workflow
import java.lang.reflect.Field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class WorkflowsTest {

    private val sampleYamlWorkflow = """
        document:
          dsl: '1.0.0'
          namespace: test
          name: test-workflow
          version: '1.0.0'
        do:
          - hello:
              call: log
              with:
                message: "Hello, World!"
    """.trimIndent()

    private val sampleJsonWorkflow = """
        {
          "document": {
            "dsl": "1.0.0",
            "namespace": "test",
            "name": "test-workflow-json",
            "version": "1.0.0"
          },
          "do": [
            {
              "hello": {
                "call": "log",
                "with": {
                  "message": "Hello, World!"
                }
              }
            }
          ]
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        // Clear caches before each test for isolation
        clearWorkflowCaches()
    }

    /**
     * Utility method to clear the caches in the Workflows object for test isolation
     */
    private fun clearWorkflowCaches() {
        // Use reflection to access and clear the cache maps
        val workflowCacheField: Field = Workflows::class.java.getDeclaredField("workflowCache")
        workflowCacheField.isAccessible = true
        val workflowCache = workflowCacheField.get(null)
        workflowCache.javaClass.getMethod("clear").invoke(workflowCache)

        val rootNodesCacheField: Field = Workflows::class.java.getDeclaredField("rootNodesCache")
        rootNodesCacheField.isAccessible = true
        val rootNodesCache = rootNodesCacheField.get(null)
        rootNodesCache.javaClass.getMethod("clear").invoke(rootNodesCache)
    }

    @Test
    fun `parse should successfully parse a YAML workflow definition`() {
        // When
        val workflow = Workflows.parse(sampleYamlWorkflow)

        // Then
        workflow.shouldBeInstanceOf<Workflow>()
        workflow.document.name shouldBe "test-workflow"
        workflow.document.version shouldBe "1.0.0"
    }

    @Test
    fun `parse should successfully parse a JSON workflow definition`() {
        // When
        val workflow = Workflows.parse(sampleJsonWorkflow)

        // Then
        workflow.shouldBeInstanceOf<Workflow>()
        workflow.document.name shouldBe "test-workflow-json"
        workflow.document.version shouldBe "1.0.0"
    }

    @Test
    fun `parse should throw exception when given an invalid workflow definition`() {
        // Given
        val invalidWorkflow = """
            document:
              invalid: yaml
              format: true
        """.trimIndent()

        // Then
        assertThrows<Exception> {
            Workflows.parse(invalidWorkflow)
        }
    }

    @Test
    fun `parseAndPut should cache the workflow and its root node`() {
        // When
        val workflow = Workflows.parseAndPut(sampleYamlWorkflow)

        // Then
        workflow.shouldBeInstanceOf<Workflow>()

        // Verify the workflow is in the cache
        val cachedWorkflow = Workflows.getOrNull(workflow.document.name, workflow.document.version)
        cachedWorkflow shouldNotBe null
        cachedWorkflow?.document?.name shouldBe workflow.document.name

        // Verify the root node is created and cached
        val rootNode = Workflows.getRootNode(workflow)
        rootNode.shouldBeInstanceOf<Node<RootTask>>()
        rootNode.task.shouldBeInstanceOf<RootTask>()
    }

    @Test
    fun `parseAndPut should validate workflow definitions`() {
        // Given
        val invalidWorkflow = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: invalid-workflow
              # Missing required version field
            do:
              - hello:
                  call: log
                  with:
                    message: "Hello, World!"
        """.trimIndent()

        // Then
        assertThrows<Exception> {
            Workflows.parseAndPut(invalidWorkflow)
        }
    }

    @Test
    fun `getOrNull should return null for non-existent workflow`() {
        // When
        val result = Workflows.getOrNull("non-existent", "1.0.0")

        // Then
        result shouldBe null
    }

    @Test
    fun `getOrNull should return cached workflow`() {
        // Given
        val workflow = Workflows.parseAndPut(sampleYamlWorkflow)

        // When
        val result = Workflows.getOrNull(workflow.document.name, workflow.document.version)

        // Then
        result shouldBe workflow
    }

    @Test
    fun `getRootNode should create and cache root node for workflow`() {
        // Given
        val workflow = validation().read(sampleYamlWorkflow, WorkflowFormat.YAML)

        // When
        val rootNode = Workflows.getRootNode(workflow)

        // Then
        rootNode.shouldBeInstanceOf<Node<RootTask>>()
        rootNode.name shouldBe "workflow"
        rootNode.parent shouldBe null

        // Call again to verify caching
        val cachedRootNode = Workflows.getRootNode(workflow)
        cachedRootNode shouldBe rootNode
    }

    @Test
    fun `getRootNode creates a root node with the correct structure`() {
        // Given - a simple workflow
        val workflow = Workflows.parseAndPut(sampleYamlWorkflow)

        // When
        val rootNode = Workflows.getRootNode(workflow)

        // Then - just verify the structure is correct
        rootNode.shouldBeInstanceOf<Node<RootTask>>()
        rootNode.task.shouldBeInstanceOf<RootTask>()
        rootNode.name shouldBe "workflow"
        rootNode.parent shouldBe null
    }

    @Test
    fun `workflow index should work correctly`() {
        // Given
        val workflow = Workflows.parseAndPut(sampleYamlWorkflow)
        val workflowName = workflow.document.name
        val workflowVersion = workflow.document.version

        // When
        val index = workflow.index

        // Then
        index.first shouldBe workflowName
        index.second shouldBe workflowVersion

        // Verify the index is used correctly for caching
        val cachedWorkflow = Workflows.getOrNull(workflowName, workflowVersion)
        cachedWorkflow shouldBe workflow
    }

    @Test
    fun `parse should handle syntax errors in YAML gracefully by trying JSON`() {
        // Given a YAML workflow with invalid syntax, but valid as JSON
        val badYamlGoodJson = """
        {"document":{"dsl":"1.0.0","namespace":"test","name":"invalid-yaml-good-json","version":"1.0.0"},
        "do":[{"hello":{"call":"log","with":{"message":"Hello, World!"}}}]}
        """.trimIndent()

        // When parsing - it should fall back to JSON parsing
        val workflow = assertDoesNotThrow {
            Workflows.parse(badYamlGoodJson)
        }

        // Then
        workflow.shouldBeInstanceOf<Workflow>()
        workflow.document.name shouldBe "invalid-yaml-good-json"
    }

    @Test
    fun `parse should throw exception when both YAML and JSON parsing fail`() {
        // Given a string that is neither valid YAML nor valid JSON
        val invalidDefinition = """
        This is neither valid YAML nor valid JSON.
        """.trimIndent()

        // Then both YAML and JSON parsing should fail
        assertThrows<Exception> {
            Workflows.parse(invalidDefinition)
        }
    }

    @Test
    fun `concurrent access to workflow cache should work correctly`() = runTest {
        // Create multiple workflows with unique names
        val workflows = (1..10).map { i ->
            """
            document:
              dsl: '1.0.0'
              namespace: test
              name: test-workflow-$i
              version: '1.0.0'
            do:
              - hello:
                  call: log
                  with:
                    message: "Hello from workflow $i"
            """.trimIndent()
        }

        // Process workflows concurrently
        val results = workflows.map { yaml ->
            async(Dispatchers.Default) {
                Workflows.parseAndPut(yaml)
            }
        }.awaitAll()

        // Verify all workflows were cached correctly
        results.forEachIndexed { i, workflow ->
            val name = workflow.document.name
            val version = workflow.document.version
            val cachedWorkflow = Workflows.getOrNull(name, version)

            // Should be in cache
            cachedWorkflow shouldBe workflow

            // Name should match the expected pattern
            name shouldBe "test-workflow-${i + 1}"
        }
    }

    @Test
    fun `concurrent access to root node cache should work correctly`() = runBlocking {
        // Create a workflow to test with
        val workflow = Workflows.parseAndPut(sampleYamlWorkflow)

        // Access the root node concurrently from multiple coroutines
        val results = (1..10).map {
            async(Dispatchers.Default) {
                Workflows.getRootNode(workflow)
            }
        }.awaitAll()

        // All returned root nodes should be the same instance
        val firstNode = results.first()
        results.forEach { node ->
            node shouldBe firstNode
        }
    }

    @Test
    fun `parse should handle complex workflow structures`() {
        // Given a more complex workflow with sequential steps only (to avoid potential parsing issues)
        val complexWorkflow = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: complex-workflow
              version: '1.0.0'
            do:
              - firstStep:
                  call: log
                  with:
                    message: "Starting workflow"
              - secondStep:
                  call: log
                  with:
                    message: "Middle step"
              - lastStep:
                  call: log
                  with:
                    message: "Workflow completed"
        """.trimIndent()

        // When
        val workflow = assertDoesNotThrow {
            Workflows.parse(complexWorkflow)
        }

        // Then
        workflow.shouldBeInstanceOf<Workflow>()
        workflow.document.name shouldBe "complex-workflow"

        // Verify the structure is parsed correctly
        workflow.`do`?.size shouldBe 3
    }
}
