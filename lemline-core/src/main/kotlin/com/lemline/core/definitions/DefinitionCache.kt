// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.definitions

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowIndex
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.common.values.index
import com.lemline.common.values.name
import com.lemline.common.values.namespace
import com.lemline.common.values.version
import com.lemline.core.nodes.Node
import com.lemline.common.values.NodePosition
import com.lemline.core.nodes.RootTask
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader
import io.serverlessworkflow.api.types.Workflow
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.TestOnly

object DefinitionCache {

    private val workflowCache = ConcurrentHashMap<WorkflowIndex, Workflow>()
    private val nodesMapCache = ConcurrentHashMap<WorkflowIndex, Map<NodePosition, Node<*>>>()

    private val jsonMapper = LemlineJson.jacksonMapper
    private val yamlMapper = LemlineJson.yamlMapper

    /**
     * Parses a workflow definition provided as a string. The method attempts to parse the definition
     * first as YAML. If YAML parsing fails, it falls back to parsing as JSON.
     * The method returns a Workflow object if parsing is successful.
     *
     * @param definition The workflow definition provided as a string. This can be a YAML or JSON formatted string.
     * @return The parsed Workflow object.
     * @throws Exception If both YAML and JSON parsing fail, an exception is thrown.
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
     * Parses the given workflow definition string in either YAML or JSON format,
     * validates it, and adds it to the cache. If the definition is successfully parsed,
     * it is cached along with its root node and nodes map for efficient retrieval.
     *
     * @param definition The workflow definition as a string, expected to be in YAML or JSON format.
     * @return The parsed and validated Workflow object.
     */
    @JvmStatic
    fun parseAndPut(definition: String): Workflow =
        try {
            WorkflowReader.validation().read(definition, WorkflowFormat.YAML)
        } catch (e: Exception) {
            println(e)
            WorkflowReader.validation().read(definition, WorkflowFormat.JSON)
        }.also { workflow ->
            workflowCache[workflow.index] = workflow
            nodesMapCache[workflow.index] = getNodesMap(createRootNode(workflow))
        }

    /**
     * Retrieves a workflow from the workflow cache, uniquely identified by its namespace, name, and version.
     *
     * @param namespace The namespace of the workflow to retrieve.
     * @param name The name of the workflow to retrieve.
     * @param version The version of the workflow to retrieve.
     * @return The corresponding Workflow object if found in the cache, or null if not found.
     */
    @JvmStatic
    fun getWorkflow(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Workflow? = workflowCache[WorkflowIndex(namespace, name, version)]

    /**
     * Retrieves a map of node positions to their corresponding nodes for a given workflow.
     *
     * @param workflow The workflow whose nodes map is to be retrieved.
     * @return A map where the keys are instances of NodePosition representing the positions of nodes,
     *         and the values are the corresponding Node objects.
     * @throws IllegalStateException if the workflow is not found in the cache.
     */
    @JvmStatic
    fun getNodesMap(
        workflow: Workflow
    ): Map<NodePosition, Node<*>> = nodesMapCache[workflow.index]
        ?: throw IllegalStateException("Workflow not found in cache for ${workflow.index}")


    /**
     * Retrieves the root node of the given workflow. The root node represents
     * the entry point of the workflow's task hierarchy.
     *
     * @param workflow The workflow whose root node is to be retrieved.
     * @return The root node of the workflow, represented as a `Node<RootTask>`.
     * @throws IllegalStateException If the nodes map for the workflow is not found in the cache
     * or if the root node is not found in the nodes map.
     */
    @JvmStatic
    fun getRootNode(
        workflow: Workflow
    ): Node<RootTask> {
        val nodesMap = nodesMapCache[workflow.index]
            ?: throw IllegalStateException("Nodes map not found in cache for ${workflow.index}")

        val rootNode = nodesMap[NodePosition.root]
            ?: throw IllegalStateException("Root node not found in nodes for ${workflow.index}")

        @Suppress("UNCHECKED_CAST")
        return rootNode as Node<RootTask>
    }

    /**
     * Retrieves the root node of the given workflow.
     * The root node is the Node<RootTask> at the root level of the workflow.
     */
    private fun createRootNode(workflow: Workflow): Node<RootTask> = Node(
        position = NodePosition.root,
        task = RootTask(workflow.document, workflow.`do`, workflow.use).also {
            it.output = workflow.output
            it.input = workflow.input
        },
        name = NodePosition.root.toString(),
        parent = null,
    )

    /**
     * Constructs a map of all nodes in a hierarchical tree starting from the given root node.
     * Each node is mapped to its position in the workflow.
     */
    private fun getNodesMap(nodeRoot: Node<RootTask>): Map<NodePosition, Node<*>> {

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
        nodesMapCache.clear()
    }
}

/**
 * Retrieves a node from the workflow based on its position.
 *
 * @param position The position of the node within the workflow.
 * @return The node corresponding to the specified position in the workflow.
 * @throws IllegalStateException If the node is not found at the specified position.
 */
fun Workflow.getNode(position: NodePosition): Node<*> {
    val nodesMap = DefinitionCache.getNodesMap(this)
    return nodesMap[position]
        ?: throw IllegalStateException(
            "Node not found at position $position in workflow: ${this.namespace}/${this.name}/${this.version}"
        )
}
