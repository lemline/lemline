// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

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
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.utils.convertFilter
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy
import io.serverlessworkflow.api.types.EventConsumptionStrategy
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.ListenTaskConfiguration
import io.serverlessworkflow.api.types.OneEventConsumptionStrategy
import io.serverlessworkflow.api.types.Until
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import io.serverlessworkflow.api.types.EventFilter as SdkEventFilter
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

@Suppress("unused")
object WorkflowCache {

    private val workflowCache = ConcurrentHashMap<WorkflowInfo, Workflow>()
    private val nodesMapCache = ConcurrentHashMap<WorkflowInfo, Map<NodePosition, Node<*>>>()
    private val listenTasksCache = ConcurrentHashMap<WorkflowInfo, List<CachedListenTask>>()

    private val jsonMapper = LemlineJson.jacksonMapper
    private val yamlMapper = LemlineJson.yamlMapper

    /**
     * Parses the given workflow definition string in YAML format
     */
    @JvmStatic
    fun parseYaml(definition: String): Workflow {
        // Parse the YAML string into a JSON node
        val jsonNode = yamlMapper.readTree(definition)

        // Project the JSON node to a Workflow object
        return jsonMapper.treeToValue(jsonNode, Workflow::class.java)
    }

    /**
     * Parses the given workflow definition string in JSON format
     */
    @JvmStatic
    fun parseJson(definition: String): Workflow {
        // Parse the JSON string into a JsonNode
        val jsonNode = jsonMapper.readTree(definition)

        // Project the JSON node to a Workflow object
        return jsonMapper.treeToValue(jsonNode, Workflow::class.java)
    }

    @JvmStatic
    fun parseYamlAndPut(definition: String): Workflow = WorkflowReader
        .validation()
        .read(definition, WorkflowFormat.YAML)
        .also { workflow -> cacheWorkflow(workflow) }

    @JvmStatic
    fun parseYamlAndPutNoValidation(definition: String): Workflow = WorkflowReader
        .noValidation()
        .read(definition, WorkflowFormat.YAML)
        .also { workflow -> cacheWorkflow(workflow) }

    /**
     * Parses the given workflow definition string in JSON format
     * and puts it into the cache.
     */
    @JvmStatic
    fun parseJsonAndPut(definition: String): Workflow = WorkflowReader
        .validation()
        .read(definition, WorkflowFormat.JSON)
        .also { workflow -> cacheWorkflow(workflow) }

    /**
     * Caches a workflow along with its parsed node tree and listen tasks.
     */
    private fun cacheWorkflow(workflow: Workflow) {
        workflowCache[workflow.info] = workflow
        val (_, nodesMap) = WorkflowParser.parse(workflow)
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
    ): Workflow? = WorkflowInfo(namespace, name, version).getWorkflow()

    /**
     * Retrieves a workflow from the workflow cache using its WorkflowInfo.
     */
    fun WorkflowInfo.getWorkflow(): Workflow? = workflowCache[this]

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

            val (strategy, sdkFilters, until) = when (listenTo) {
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

            // Allow empty filters for ANY strategy (any: [] means "match any event")
            // For ONE and ALL strategies, filters are required
            if (sdkFilters.isNotEmpty() || strategy == ListenStrategy.ANY) {
                val readAs = listenTask.listen?.read
                    ?: ListenTaskConfiguration.ListenAndReadAs.DATA

                listenTasks.add(
                    CachedListenTask(
                        workflowInfo = workflowInfo,
                        nodePosition = position,
                        filters = sdkFilters.map { convertFilter(it) },
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

    private fun extractUntilEventFilter(strategy: EventConsumptionStrategy): EventFilter? {
        val sdkFilter: SdkEventFilter? = when (val strategyValue = strategy.get()) {
            is OneEventConsumptionStrategy -> strategyValue.one
            is AnyEventConsumptionStrategy -> strategyValue.any?.firstOrNull()
            is AllEventConsumptionStrategy -> strategyValue.all?.firstOrNull()
            else -> null
        }
        return sdkFilter?.let { convertFilter(it) }
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
    val nodesMap = WorkflowCache.getNodesMap(this)
    return nodesMap[position]
        ?: throw IllegalStateException(
            "Node not found at position $position in workflow: ${this.namespace}/${this.name}/${this.version}"
        )
}
