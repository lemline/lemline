// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.workflows.WorkflowCache
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.Workflow

inline fun <reified T> ObjectNode.set(key: String, value: T) =
    ObjectNode(toMap().toMutableMap().apply { set(key, LemlineJson.encodeToElement(value)) })

internal fun load(resourcePath: String): String {
    val inputStream = WorkflowCache::class.java.getResourceAsStream(resourcePath)
        ?: throw IllegalArgumentException("Resource not found: $resourcePath")

    return inputStream.bufferedReader().use { it.readText() }
}

internal fun loadWorkflowFromYaml(resourcePath: String): Workflow {
    val yamlContent = load(resourcePath)
    return validation().read(yamlContent, WorkflowFormat.YAML)
}

/**
 * Helper function to get workflow node for testing CompleteOrchestrator.
 *
 * @param doYaml The workflow do section in YAML format (will have @ replaced with $)
 * @param namespace Optional namespace
 * @param name Optional workflow name
 * @param version Optional workflow version
 * @return Root Node for use with CompleteOrchestrator.run()
 */
internal fun getRootNodeOfWorkflowToTest(
    doYaml: String,
    namespace: String = "test",
    name: String = "workflow-${doYaml.hashCode()}",
    version: String = "0.1.0",
): Node<RootTask> {
    val workflow = getWorkflowToTest(doYaml, namespace, name, version)

    return WorkflowCache.getRootNode(workflow)
}

internal fun getWorkflowToTest(
    doYaml: String,
    namespace: String = "test",
    name: String = "workflow-${doYaml.hashCode()}",
    version: String = "0.1.0",
): Workflow {
    val document =
        """document:
              dsl: '1.0.3'
              namespace: $namespace
              name: $name
              version: $version
        """
    val workflowYaml = document.trimIndent() + "\n" + doYaml.trimIndent()
    return WorkflowCache.parseYamlAndPut(workflowYaml)
}
