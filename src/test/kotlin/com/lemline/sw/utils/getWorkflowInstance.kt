package com.lemline.sw.utils

import com.lemline.sw.nodes.NodeState
import com.lemline.sw.workflows.WorkflowInstance
import com.lemline.sw.workflows.WorkflowParser
import com.lemline.worker.messaging.WorkflowMessage
import com.lemline.worker.models.WorkflowDefinition
import com.lemline.worker.repositories.WorkflowDefinitionRepository
import io.mockk.every
import io.mockk.mockk
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import kotlinx.serialization.json.JsonElement

internal fun getWorkflowInstance(doYaml: String, input: JsonElement): WorkflowInstance {
    val hash = doYaml.hashCode()
    val document = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: do-nested-$hash
              version: '0.1.0'
        """.trimIndent()
    val workflowYaml = document + "\n" + doYaml.trimIndent().replace("@", "$")
    val workflow = validation().read(workflowYaml, WorkflowFormat.YAML)
    val workflowName = workflow.document.name
    val workflowVersion = workflow.document.version
    val workflowDefinition = WorkflowDefinition().apply {
        name = workflowName
        version = workflowVersion
        definition = workflowYaml
    }

    // create a mocked repository
    val mockedRepository = mockk<WorkflowDefinitionRepository>().apply {
        every { findByNameAndVersion(workflowName, workflowVersion) } returns workflowDefinition
    }

    // apply it to the WorkflowService instance
    val mockedService = WorkflowParser(mockedRepository)

    val msg = WorkflowMessage.newInstance(workflowName, workflowVersion, "testId", input)

    return WorkflowInstance(
        name = msg.name,
        version = msg.version,
        states = msg.states
            .mapKeys { it.key.toPosition() }
            .mapValues { NodeState.fromJson(it.value) }
            .toMutableMap(),
        position = msg.position.toPosition()
    ).apply { workflowParser = mockedService }
}
