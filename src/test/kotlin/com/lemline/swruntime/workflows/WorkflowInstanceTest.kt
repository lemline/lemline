package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.messaging.WorkflowExecutionMessage
import com.lemline.swruntime.models.WorkflowDefinition
import com.lemline.swruntime.repositories.WorkflowDefinitionRepository
import com.lemline.swruntime.system.System
import io.mockk.every
import io.mockk.mockk
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkflowInstanceTest {

    @BeforeEach
    fun setUp() {
        // restore System env variables
        System.restoreEnv()
    }

    @Test
    fun `test getWorkflow used cache`() = runTest {
        val instance = getWorkflowInstance("/examples/do-nested.yaml", JsonUtils.fromValue(listOf(1, 2, 3)))

        do {
            instance.run()
            println(instance.position.jsonPointer)
        } while (!instance.isCompleted())
    }

    private fun getWorkflowInstance(path: String, input: JsonNode): WorkflowInstance {
        val workflow = loadWorkflowFromYaml(path)
        val workflowName = workflow.document.name
        val workflowVersion = workflow.document.version
        val workflowDefinition = WorkflowDefinition().apply {
            name = workflowName
            version = workflowVersion
            definition = load(path)
        }

        // create a mocked repository
        val mockedRepository = mockk<WorkflowDefinitionRepository>().apply {
            every { findByNameAndVersion(workflowName, workflowVersion) } returns workflowDefinition
        }

        // apply it to the WorkflowService instance
        val mockedService = WorkflowService(mockedRepository)

        val msg = WorkflowExecutionMessage.create(workflowName, workflowVersion, "testId", input)

        return WorkflowInstance(
            name = msg.name,
            version = msg.version,
            state = msg.state.mapKeys { state -> state.key.toPosition() }.toMutableMap(),
            position = msg.position.toPosition()
        ).apply { workflowService = mockedService }
    }

    private fun load(resourcePath: String): String {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun loadWorkflowFromYaml(resourcePath: String): Workflow {
        val yamlContent = load(resourcePath)
        return validation().read(yamlContent, WorkflowFormat.YAML)
    }

}
