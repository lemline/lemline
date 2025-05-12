// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.RootTask
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.Workflow
import java.util.concurrent.ConcurrentHashMap

object Workflows {

    private val workflowCache = ConcurrentHashMap<WorkflowIndex, Workflow>()
    private val rootNodesCache = ConcurrentHashMap<WorkflowIndex, Node<RootTask>>()

    private val jsonMapper = LemlineJson.jacksonMapper
    private val yamlMapper = LemlineJson.yamlMapper

    /**
     * Parses a workflow definition string into a `Workflow` object.
     *
     * This method detects the format of the provided workflow definition (JSON or YAML)
     * by actually attempting to decode the string as YAML first.
     *
     * @param definition The workflow definition as a string.
     * @return The parsed `Workflow` object.
     * @throws Exception if the workflow definition cannot be parsed.
     */
    @JvmStatic
    fun parse(definition: String): Workflow {
        // Try to parse as YAML first, then as JSON
        val jsonNode = try {
            yamlMapper.readTree(definition)
        } catch (_: Exception) {
            jsonMapper.readTree(definition)
        }

        // Project the JSON node to a Workflow object
        return jsonMapper.treeToValue(jsonNode, Workflow::class.java)
    }

    /**
     * Adds a workflow definition to the cache.
     *
     * This method parses the provided workflow definition in YAML format, validates it,
     * and stores it in the workflow cache. It also parses and caches the workflow's nodes.
     *
     * @param definition The YAML string representing the workflow definition.
     * @return The parsed and validated Workflow object.
     * @throws IllegalStateException if the workflow definition is invalid.
     */
    @JvmStatic
    fun parseAndPut(definition: String): Workflow = validation().read(definition, WorkflowFormat.YAML).also {
        workflowCache[it.index] = it
        rootNodesCache[it.index] = getRootNode(it)
    }

    /**
     * Retrieves a workflow definition by its name and version.
     *
     * This method uses the `getOrPut` function to either fetch the workflow from the cache
     * or execute the provided factory function to handle the case where the workflow is not found.
     * If the workflow is not found, an error is thrown.
     *
     * @param name The name of the workflow.
     * @param version The version of the workflow.
     * @return The workflow definition.
     * @throws IllegalStateException if the workflow is not found.
     */
    @JvmStatic
    fun getOrNull(name: String, version: String): Workflow? = workflowCache[name to version]

    /**
     * Retrieves the root node of the given workflow.
     * The root node is the Node<RootTask> at the root level of the workflow.
     *
     * @param workflow The workflow containing the root node.
     * @return The root node of the workflow.
     */
    @JvmStatic
    fun getRootNode(workflow: Workflow): Node<RootTask> = rootNodesCache.getOrPut(workflow.index) {
        Node(
            position = NodePosition.root,
            task = RootTask(workflow.document, workflow.`do`, workflow.use).also {
                it.output = workflow.output
                it.input = workflow.input
            },
            name = "workflow",
            parent = null,
        )
    }
}
