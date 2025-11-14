// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.definitions

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.RootTask
import com.lemline.core.workflows.WorkflowIndex
import com.lemline.core.workflows.index
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader
import io.serverlessworkflow.api.types.Workflow
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.TestOnly

object DefinitionCache {

    private val workflowCache = ConcurrentHashMap<WorkflowIndex, Workflow>()
    private val rootNodesCache = ConcurrentHashMap<WorkflowIndex, Node<RootTask>>()
    private val nodesMapCache = ConcurrentHashMap<WorkflowIndex, Map<NodePosition, Node<*>>>()

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
    fun parseAndPut(definition: String): Workflow =
        WorkflowReader.validation().read(definition, WorkflowFormat.YAML).also { workflow ->
            workflowCache[workflow.index] = workflow
            rootNodesCache[workflow.index] = getRootNode(workflow).also {
                nodesMapCache[workflow.index] = getNodeMap(it)
            }
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
    fun getWorkflowFromCache(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Workflow? = workflowCache[WorkflowIndex(namespace, name, version)]

    /**
     * Retrieves a map of nodes corresponding to a specific workflow, identified by namespace, name, and version.
     *
     * @param namespace The namespace of the workflow.
     * @param name The name of the workflow.
     * @param version The version of the workflow.
     * @return A map where the keys are node positions and the values are the corresponding nodes, or null if no such map exists.
     */
    @JvmStatic
    fun getNodesMapFromCache(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Map<NodePosition, Node<*>>? = nodesMapCache[WorkflowIndex(namespace, name, version)]


    /**
     * Retrieves the root node of a workflow from the cache using the specified namespace, name, and version.
     *
     * @param namespace The namespace of the workflow.
     * @param name The name of the workflow.
     * @param version The version of the workflow.
     * @return The root node of the workflow, or null if not found in the cache.
     */
    @JvmStatic
    fun getRootNodeFromCache(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Node<RootTask>? = rootNodesCache[WorkflowIndex(namespace, name, version)]

    /**
     * Retrieves the root node of the given workflow.
     * The root node is the Node<RootTask> at the root level of the workflow.
     */
    internal fun getRootNode(workflow: Workflow): Node<RootTask> = rootNodesCache.getOrPut(workflow.index) {
        Node(
            position = NodePosition.root,
            task = RootTask(workflow.document, workflow.`do`, workflow.use).also {
                it.output = workflow.output
                it.input = workflow.input
            },
            name = NodePosition.root.toString(),
            parent = null,
        )
    }

    /**
     * Constructs a map of all nodes in a hierarchical tree starting from the given root node.
     * Each node is mapped to its position in the workflow.
     */
    private fun getNodeMap(nodeRoot: Node<RootTask>): Map<NodePosition, Node<*>> {

        val map = mutableMapOf<NodePosition, Node<*>>()

        fun addNodeAndChildren(node: Node<*>) {
            // Add the current node to the map
            map[node.position] = node

            // Recursively add all children
            node.children?.forEach { child ->
                addNodeAndChildren(child)
            }
        }

        // Start the recursive traversal from the root
        addNodeAndChildren(nodeRoot)

        return map
    }

    @TestOnly
    fun clear() {
        workflowCache.clear()
        rootNodesCache.clear()
        nodesMapCache.clear()
    }
}
