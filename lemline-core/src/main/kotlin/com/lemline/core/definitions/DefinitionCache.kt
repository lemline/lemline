// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.definitions

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.common.values.info
import com.lemline.common.values.name
import com.lemline.common.values.namespace
import com.lemline.common.values.version
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.processors.ListenStrategy
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy
import io.serverlessworkflow.api.types.EventConsumptionStrategy
import io.serverlessworkflow.api.types.EventFilter
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.ListenTaskConfiguration
import io.serverlessworkflow.api.types.OneEventConsumptionStrategy
import io.serverlessworkflow.api.types.Until
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.TestOnly

/**
 * Cached until condition for ANY + until accumulation mode.
 * Pre-parsed from workflow definition for efficient CloudEvent matching.
 */
sealed class CachedUntilCondition {
    /**
     * Expression evaluated against accumulated events array.
     * E.g., ". | length > 3" or ". | any(.temperature > 38)"
     */
    data class Expression(val expression: String) : CachedUntilCondition()

    /**
     * Termination event filter - stop accumulating when this event arrives.
     * Stores the filter to match against incoming events.
     */
    data class Event(val filter: EventFilter) : CachedUntilCondition()
}

/**
 * Cached information about a listen task in a workflow.
 * Used for efficient CloudEvent matching without re-parsing workflow definitions.
 */
data class CachedListenTask(
    val workflowInfo: WorkflowInfo,
    val nodePosition: NodePosition,
    val filters: List<EventFilter>,
    val strategy: ListenStrategy,
    val readAs: ListenTaskConfiguration.ListenAndReadAs,
    /** Until condition for ANY + until accumulation mode (null for ONE, ANY without until, ALL) */
    val until: CachedUntilCondition? = null,
    /** Whether this listen task has foreach enabled for sequential event processing */
    val hasForeach: Boolean = false
) {
    /**
     * Returns the termination filter if this is an ANY + until(event) task.
     */
    val untilEventFilter: EventFilter?
        get() = (until as? CachedUntilCondition.Event)?.filter
}

object DefinitionCache {

    private val workflowCache = ConcurrentHashMap<WorkflowInfo, Workflow>()
    private val nodesMapCache = ConcurrentHashMap<WorkflowInfo, Map<NodePosition, Node<*>>>()
    private val listenTasksCache = ConcurrentHashMap<WorkflowInfo, List<CachedListenTask>>()

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
     * it is cached along with its root node, nodes map, and listen tasks for efficient retrieval.
     *
     * @param definition The workflow definition as a string, expected to be in YAML or JSON format.
     * @return The parsed and validated Workflow object.
     */
    @JvmStatic
    fun parseAndPut(definition: String): Workflow =
        try {
            WorkflowReader.validation().read(definition, WorkflowFormat.YAML)
        } catch (_: Exception) {
            WorkflowReader.validation().read(definition, WorkflowFormat.JSON)
        }.also { workflow ->
            workflowCache[workflow.info] = workflow
            val nodesMap = getNodesMap(createRootNode(workflow))
            nodesMapCache[workflow.info] = nodesMap
            listenTasksCache[workflow.info] = extractListenTasks(workflow.info, nodesMap)
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
    ): Workflow? = workflowCache[WorkflowInfo(namespace, name, version)]

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
    ): Map<NodePosition, Node<*>> = nodesMapCache[workflow.info]
        ?: throw IllegalStateException("Workflow not found in cache for ${workflow.info}")


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
        val nodesMap = nodesMapCache[workflow.info]
            ?: throw IllegalStateException("Nodes map not found in cache for ${workflow.info}")

        val rootNode = nodesMap[NodePosition.root]
            ?: throw IllegalStateException("Root node not found in nodes for ${workflow.info}")

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

    /**
     * Removes a workflow from the cache.
     *
     * @param namespace The namespace of the workflow to remove.
     * @param name The name of the workflow to remove.
     * @param version The version of the workflow to remove.
     */
    @JvmStatic
    fun remove(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ) {
        val key = WorkflowInfo(namespace, name, version)
        workflowCache.remove(key)
        nodesMapCache.remove(key)
        listenTasksCache.remove(key)
    }

    /**
     * Returns all cached workflows.
     *
     * This is used for iterating over all workflows, for example when
     * matching CloudEvents against listen task filters.
     *
     * @return A list of all cached workflows.
     */
    @JvmStatic
    fun getAllWorkflows(): List<Workflow> = workflowCache.values.toList()

    /**
     * Returns all cached listen tasks across all workflows.
     *
     * This is used for efficient CloudEvent matching without re-parsing
     * workflow definitions on every event.
     *
     * @return A list of all cached listen tasks.
     */
    @JvmStatic
    fun getAllListenTasks(): List<CachedListenTask> = listenTasksCache.values.flatten()

    /**
     * Returns cached listen tasks for a specific workflow.
     *
     * @param workflowInfo The workflow to get listen tasks for.
     * @return The list of listen tasks, or empty list if workflow not found.
     */
    @JvmStatic
    fun getListenTasks(workflowInfo: WorkflowInfo): List<CachedListenTask> =
        listenTasksCache[workflowInfo] ?: emptyList()

    /**
     * Extracts listen tasks from a workflow's nodes map.
     * Called once when workflow is cached.
     */
    private fun extractListenTasks(
        workflowInfo: WorkflowInfo,
        nodesMap: Map<NodePosition, Node<*>>
    ): List<CachedListenTask> {
        val listenTasks = mutableListOf<CachedListenTask>()

        for ((position, node) in nodesMap) {
            val listenTask = node.task as? ListenTask ?: continue
            val listenTo = listenTask.listen?.to?.get() ?: continue

            val (strategy, filters, until) = when (listenTo) {
                is OneEventConsumptionStrategy -> Triple(
                    ListenStrategy.ONE,
                    listOfNotNull(listenTo.one),
                    null
                )

                is AnyEventConsumptionStrategy -> Triple(
                    ListenStrategy.ANY,
                    listenTo.any ?: emptyList(),
                    parseUntilCondition(listenTo.until)
                )

                is AllEventConsumptionStrategy -> Triple(
                    ListenStrategy.ALL,
                    listenTo.all ?: emptyList(),
                    null
                )

                else -> continue
            }

            if (filters.isNotEmpty()) {
                val readAs = listenTask.listen?.read
                    ?: ListenTaskConfiguration.ListenAndReadAs.DATA

                listenTasks.add(
                    CachedListenTask(
                        workflowInfo = workflowInfo,
                        nodePosition = position,
                        filters = filters,
                        strategy = strategy,
                        readAs = readAs,
                        until = until,
                        hasForeach = listenTask.foreach != null
                    )
                )
            }
        }

        return listenTasks
    }

    /**
     * Parses the until condition from the workflow definition.
     * Returns null if no until condition is specified.
     */
    private fun parseUntilCondition(until: Until?): CachedUntilCondition? {
        if (until == null) return null

        return when (val value = until.get()) {
            is String -> {
                // Expression condition evaluated against accumulated events
                val expr = if (ExpressionUtils.isExpr(value)) {
                    ExpressionUtils.trimExpr(value)
                } else {
                    value
                }
                CachedUntilCondition.Expression(expr)
            }

            is EventConsumptionStrategy -> {
                // Event filter - stop when this event arrives
                val filter = extractUntilEventFilter(value) ?: return null
                CachedUntilCondition.Event(filter)
            }

            else -> null
        }
    }

    /**
     * Extracts the event filter from an until EventConsumptionStrategy.
     * The spec allows nested consumption strategies for until, but we simplify
     * to a single filter for the termination event.
     */
    private fun extractUntilEventFilter(strategy: EventConsumptionStrategy): EventFilter? {
        return when (val strategyValue = strategy.get()) {
            is OneEventConsumptionStrategy -> strategyValue.one
            is AnyEventConsumptionStrategy -> strategyValue.any?.firstOrNull()
            is AllEventConsumptionStrategy -> strategyValue.all?.firstOrNull()
            else -> null
        }
    }

    @TestOnly
    fun clear() {
        workflowCache.clear()
        nodesMapCache.clear()
        listenTasksCache.clear()
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
