// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.common.values.info
import com.lemline.common.values.name
import com.lemline.common.values.namespace
import com.lemline.common.values.version
import com.lemline.core.expressions.EvaluationMode
import com.lemline.core.functions.FunctionCache
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeOrigin
import com.lemline.core.nodes.RootTask
import com.lemline.core.processors.ListenStrategy
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.types.CallFunction
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.ListenTaskConfiguration
import io.serverlessworkflow.api.types.Task
import io.serverlessworkflow.api.types.Workflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.TestOnly

/**
 * Cached information about a listen task in a workflow.
 * Used for efficient CloudEvent matching without re-parsing workflow definitions.
 */
data class CachedListenTask(
    val workflowInfo: WorkflowInfo,
    val nodePosition: NodePosition,
    val consumption: CachedEventConsumption,
    val readAs: ListenTaskConfiguration.ListenAndReadAs,
    /** Whether this listen task has foreach enabled for sequential event processing */
    val hasForeach: Boolean = false,
    /**
     * True if this listen task is from a function definition.
     * Function listen tasks require suffix matching since the actual listener position
     * includes the call site (e.g., `/do/0/callWait/_fn/do/0/waitForEvent`).
     */
    val isFromFunction: Boolean = false
) {
    val filters get() = consumption.filters
    val strategy get() = consumption.strategy

}

@Suppress("unused")
object WorkflowCache {

    internal data class Entry(
        val workflow: Workflow,
        val nodes: Map<NodePosition, Node<*>>,
        val listens: List<CachedListenTask>,
        val schedule: CachedEventSchedule?,
        val source: String?,
    ) {
        val functions = java.util.concurrent.ConcurrentHashMap<Pair<String, Boolean>, Pair<Task, Map<NodePosition, Node<*>>>>()
        val namedFunctionNodes by lazy {
            workflow.useFunctions.orEmpty().mapValues { (_, task) -> FunctionCache.buildFunctionNodesMap(task, workflow.use) }
        }
        val definitionJson: ObjectNode by lazy { LemlineJson.encodeToElement(workflow) }
    }

    class Snapshot internal constructor(internal val entries: Map<WorkflowInfo, Entry>)

    // Publish a workflow, its nodes and both kinds of event filters together. Writes are
    // infrequent definition changes; event matching only reads the immutable snapshot.
    @Volatile private var current = Snapshot(emptyMap())

    fun snapshot(): Snapshot = current

    /**
     * Parses the given workflow definition string in YAML format
     */
    @JvmStatic
    fun parseYaml(definition: String): Workflow {
        return WorkflowDefinitionReader.read(definition, WorkflowFormat.YAML, validate = false)
    }

    /**
     * Parses the given workflow definition string in JSON format
     */
    @JvmStatic
    fun parseJson(definition: String): Workflow {
        return WorkflowDefinitionReader.read(definition, WorkflowFormat.JSON, validate = false)
    }

    @JvmStatic
    fun parseYamlAndPut(definition: String): Workflow = WorkflowDefinitionReader
        .read(definition, WorkflowFormat.YAML)
        .also { workflow -> cacheWorkflow(workflow, definition) }

    @JvmStatic
    fun parseYamlAndPutNoValidation(definition: String): Workflow = WorkflowDefinitionReader
        .read(definition, WorkflowFormat.YAML, validate = false)
        .also { workflow -> cacheWorkflow(workflow, definition) }

    /**
     * Parses the given workflow definition string in JSON format
     * and puts it into the cache.
     */
    @JvmStatic
    fun parseJsonAndPut(definition: String): Workflow = WorkflowDefinitionReader
        .read(definition, WorkflowFormat.JSON)
        .also { workflow -> cacheWorkflow(workflow, definition) }

    /**
     * Caches a workflow along with its parsed node tree and listen tasks.
     * This is the internal implementation used by parse*AndPut methods.
     */
    private fun entry(workflow: Workflow, source: String?): Entry {
        val (_, nodes) = WorkflowParser.parse(workflow)
        return Entry(
            workflow, nodes, extractListenTasks(workflow.info, nodes, workflow),
            workflow.schedule?.on?.get()?.let {
                CachedEventSchedule(workflow.info, CachedEventConsumption.from(it, workflow.evaluationMode))
            },
            source,
        )
    }

    private fun cacheWorkflow(workflow: Workflow, source: String? = null) {
        val entry = entry(workflow, source)
        synchronized(this) {
            current = Snapshot(current.entries + (workflow.info to entry))
        }
    }

    /** Reuse parsed definitions when a locked database read confirms their source is unchanged. */
    fun refreshDefinition(info: WorkflowInfo, definition: String): Workflow {
        val snapshot = current
        snapshot.entries[info]?.takeIf { it.source == definition }?.let { return it.workflow }
        val workflow = WorkflowDefinitionReader.read(definition, WorkflowFormat.YAML)
        require(workflow.info == info) { "Stored workflow identity does not match $info" }
        val parsed = entry(workflow, definition)
        synchronized(this) {
            // Never overwrite a concurrent publication of this identity, but do not let writes
            // to other workflows drop the refresh: the command handler reads this entry next.
            if (current.entries[info] === snapshot.entries[info]) current = Snapshot(current.entries + (info to parsed))
        }
        return workflow
    }

    /**
     * Reconcile a database snapshot only if no local cache write happened since the query began.
     * This also detects remove/recreate races. A rejected refresh must be retried by the caller.
     * Programmatically-built workflows (source == null) are outside database ownership.
     */
    fun reconcile(
        snapshot: Snapshot,
        definitions: Map<WorkflowInfo, String>,
        onInvalid: (WorkflowInfo, Exception) -> Unit,
    ): Boolean {
        val entries = snapshot.entries.filterValues { it.source == null }.toMutableMap()
        for ((info, source) in definitions) {
            try {
                entries[info] = snapshot.entries[info]?.takeIf { it.source == source } ?: run {
                    val workflow = WorkflowDefinitionReader.read(source, WorkflowFormat.YAML)
                    require(workflow.info == info) { "Stored workflow identity does not match $info" }
                    entry(workflow, source)
                }
            } catch (e: Exception) {
                entries.remove(info)
                onInvalid(info, e)
            }
        }
        return synchronized(this) {
            if (current !== snapshot) false else {
                current = Snapshot(entries.toMap())
                true
            }
        }
    }

    /** Invalidate after commit without evicting a concurrent recreation of the same identity. */
    @Synchronized
    fun removeIfUnchanged(info: WorkflowInfo, snapshot: Snapshot) {
        if (current.entries[info] === snapshot.entries[info]) {
            current = Snapshot(current.entries - info)
        }
    }

    /** A stale candidate must not evict a newer definition published while the DB read was in flight. */
    @Synchronized
    fun removeEventSchedule(candidate: CachedEventSchedule) {
        if (current.entries[candidate.workflowInfo]?.schedule === candidate) {
            current = Snapshot(current.entries - candidate.workflowInfo)
        }
    }

    fun getEventConsumption(workflow: Workflow): CachedEventConsumption? {
        val entry = current.entries[workflow.info]
        return if (entry?.workflow === workflow) entry.schedule?.consumption
        else workflow.schedule?.on?.get()?.let {
            CachedEventConsumption.from(it, workflow.evaluationMode)
        }
    }

    /** Reuse the parsed JSON for the cached instance; avoid a stale entry with the same identity. */
    fun getDefinitionJson(workflow: Workflow): ObjectNode {
        val entry = current.entries[workflow.info]
        return if (entry?.workflow === workflow) entry.definitionJson else LemlineJson.encodeToElement(workflow)
    }

    fun getEventSchedules(namespace: WorkflowNamespace): List<CachedEventSchedule> =
        current.entries.values.mapNotNull { it.schedule }.filter { it.workflowInfo.namespace == namespace }

    /**
     * Caches a programmatically-built workflow.
     *
     * Use this method when you have a Workflow object built in code
     * rather than parsed from YAML/JSON.
     *
     * @param workflow The workflow to cache
     */
    @JvmStatic
    fun putWorkflow(workflow: Workflow) {
        cacheWorkflow(workflow)
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
    fun WorkflowInfo.getWorkflow(): Workflow? = current.entries[this]?.workflow

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
    ): Map<NodePosition, Node<*>> = current.entries[workflow.info]?.takeIf { it.workflow === workflow }?.nodes
        ?: throw IllegalStateException("Workflow not found in cache for ${workflow.info}")

    internal fun functionNodes(workflow: Workflow, task: Task, reference: String, suppressed: Boolean): Map<NodePosition, Node<*>> {
        val entry = current.entries[workflow.info]?.takeIf { it.workflow === workflow }
            ?: error("Workflow definition is no longer cached: ${workflow.info}")
        return entry.functions.compute(reference to suppressed) { _, previous ->
            previous?.takeIf { it.first === task }
                ?: (task to FunctionCache.buildFunctionNodesMap(task, workflow.use, suppressed))
        }!!.second
    }

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
        val nodesMap = getNodesMap(workflow)

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
        synchronized(this) { current = Snapshot(current.entries - key) }
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
    fun getAllWorkflows(): List<Workflow> = current.entries.values.map { it.workflow }

    /**
     * Returns all cached listen tasks across all workflows.
     *
     * This is used for efficient CloudEvent matching without re-parsing
     * workflow definitions on every event.
     *
     * @return A list of all cached listen tasks.
     */
    @JvmStatic
    fun getAllListenTasks(): List<CachedListenTask> = current.entries.keys.flatMap(::getListenTasks)

    /**
     * Returns cached listen tasks for a specific workflow.
     *
     * @param workflowInfo The workflow to get listen tasks for.
     * @return The list of listen tasks, or empty list if workflow not found.
     */
    @JvmStatic
    fun getListenTasks(workflowInfo: WorkflowInfo): List<CachedListenTask> =
        current.entries[workflowInfo]?.let { entry ->
            entry.listens + entry.functions.entries.flatMap { (key, value) ->
                val mode = entry.workflow.evaluationMode
                value.second.mapNotNull { (position, node) ->
                    extractListenTaskFromNode(workflowInfo, functionListenPosition(key.first, position), node, mode, false)
                }
            }
        }?.distinctBy { Triple(it.nodePosition, it.isFromFunction, it.consumption) } ?: emptyList()

    /**
     * Extracts listen tasks from a workflow's nodes map and its function definitions.
     * Called once when workflow is cached.
     */
    private fun extractListenTasks(
        workflowInfo: WorkflowInfo,
        nodesMap: Map<NodePosition, Node<*>>,
        workflow: Workflow
    ): List<CachedListenTask> {
        val listenTasks = mutableListOf<CachedListenTask>()
        val mode = workflow.evaluationMode

        // Extract from main workflow tree
        for ((position, node) in nodesMap) {
            extractListenTaskFromNode(workflowInfo, position, node, mode, isFromFunction = false)
                ?.let { listenTasks.add(it) }
        }

        // Extract from functions in use.functions
        workflow.useFunctions?.forEach { (reference, functionTask) ->
            val nodes = FunctionCache.buildFunctionNodesMap(functionTask, workflow.use)
            for ((position, node) in nodes) {
                extractListenTaskFromNode(workflowInfo, functionListenPosition(reference, position), node, mode, isFromFunction = false)?.let(listenTasks::add)
                // Keep routing pre-upgrade registrations stored at their execution positions.
                extractListenTaskFromNode(workflowInfo, position, node, mode, isFromFunction = true)?.let(listenTasks::add)
            }
        }

        return listenTasks
    }

    /**
     * Extracts a CachedListenTask from a node if it's a listen task.
     */
    private fun extractListenTaskFromNode(
        workflowInfo: WorkflowInfo,
        position: NodePosition,
        node: Node<*>,
        mode: EvaluationMode,
        isFromFunction: Boolean
    ): CachedListenTask? {
        val listenTask = node.task as? ListenTask ?: return null
        val listenTo = listenTask.listen?.to?.get() ?: return null

        val consumption = CachedEventConsumption.from(listenTo, mode)
        if (consumption.filters.isEmpty() && consumption.strategy != ListenStrategy.ANY) return null

        val readAs = listenTask.listen?.read
            ?: ListenTaskConfiguration.ListenAndReadAs.DATA

        return CachedListenTask(
            workflowInfo = workflowInfo,
            nodePosition = position,
            consumption = consumption,
            readAs = readAs,
            hasForeach = listenTask.foreach != null,
            isFromFunction = isFromFunction
        )
    }

    /** No remote I/O during parsing. Only active function registrations trigger filter hydration. */
    fun requiresFunctionListenerRefresh(namespace: WorkflowNamespace): Boolean = current.entries.values
        .filter { it.workflow.info.namespace == namespace }.any { entry ->
            val nodes = entry.nodes.values + entry.namedFunctionNodes.values.flatMap { it.values } +
                entry.functions.values.flatMap { it.second.values }
            nodes.any { node ->
                val reference = (node.task as? CallFunction)?.call
                reference != null && reference !in entry.workflow.useFunctions.orEmpty() &&
                    !entry.functions.containsKey(reference to false)
            }
        }

    suspend fun loadFunctionListeners(info: WorkflowInfo, reference: String, resolver: suspend (String) -> Task) {
        val entry = current.entries[info] ?: return
        // Only the unsuppressed tree carries extension listeners; a suppressed one does not stand in for it.
        if (entry.functions.containsKey(reference to false)) return
        val task = entry.workflow.useFunctions?.get(reference) ?: resolver(reference)
        // A definition replaced during remote resolution must not receive stale templates.
        if (current.entries[info] === entry) functionNodes(entry.workflow, task, reference, false)
    }

    fun functionReference(position: NodePosition): String? = position.toString().takeIf { it.startsWith("/_fn/") }
        ?.removePrefix("/_fn/")?.substringBefore('/')?.replace("~1", "/")?.replace("~0", "~")

    /** Stable filter identity; execution positions remain in the serialized message. */
    private fun functionListenPosition(reference: String, relative: NodePosition) = NodePosition(
        "/_fn/" + reference.replace("~", "~0").replace("/", "~1") + relative.toString().removePrefix("/_fn")
    )

    fun listenPosition(info: WorkflowInfo, runtime: NodePosition): NodePosition {
        val entry = current.entries[info] ?: return runtime
        val path = runtime.toString()
        val boundary = path.lastIndexOf("/_fn/")
        if (boundary < 0) return runtime
        val caller = entry.workflow.getNode(NodePosition(path.substring(0, boundary))) { reference ->
            entry.functions.entries.firstOrNull { it.key.first == reference }?.value?.first
                ?: error("Function '$reference' is not loaded for $info")
        }
        val reference = (caller.task as CallFunction).call
        return functionListenPosition(reference, NodePosition(path.substring(boundary)))
    }

    fun getListenTask(info: WorkflowInfo, runtime: NodePosition): CachedListenTask? {
        val position = listenPosition(info, runtime)
        return getListenTasks(info).firstOrNull { !it.isFromFunction && it.nodePosition == position }
    }

    @TestOnly
    fun clear() {
        synchronized(this) { current = Snapshot(emptyMap()) }
    }
}

internal class NodeResolutionException(val caller: Node<*>, val failure: Exception) : RuntimeException(failure)

/**
 * Retrieves a node from the workflow based on its position.
 *
 * Handles both simple positions (e.g., `/do/0/task`) and function positions
 * containing `_fn` markers (e.g., `/do/0/callFunc/_fn/do/0/step`).
 *
 * For function positions, the resolution walks through:
 * 1. Workflow tree for the initial segment (up to first `_fn`)
 * 2. Function tree(s) for subsequent segments (between `_fn` markers)
 *
 * **Important**: For function positions, a NEW node is returned with:
 * - The full composite position (as passed in)
 * - The correct parent reference (CallFunction node or parent function node)
 * - The task from the cached function tree
 *
 * @param position The position of the node within the workflow.
 * @param functionResolver Optional resolver for remote functions (URLs, catalogs).
 *                         Required when position contains remote function calls.
 * @return The node corresponding to the specified position in the workflow.
 * @throws IllegalStateException If the node is not found at the specified position.
 */
fun Workflow.getNode(
    position: NodePosition,
    functionResolver: suspend (String) -> Task
): Node<*> {
    val fnToken = "/${Token.FUN.token}"

    // Fast path: no function invocation
    if (!position.toString().contains(fnToken)) {
        val nodesMap = WorkflowCache.getNodesMap(this)
        return nodesMap[position]
            ?: throw IllegalStateException(
                "Node not found at position $position in workflow: ${this.namespace}/${this.name}/${this.version}"
            )
    }

    // Slow path: resolve through function trees
    return resolveNodeThroughFunctions(position, functionResolver)
}

/**
 * Resolves a node position that contains function invocations.
 *
 * Example position: `/do/0/calc/_fn/do/0/step1`
 *
 * This builds the complete parent chain:
 * - `/do/0/calc` → CallFunction (from workflow tree)
 * - `/do/0/calc/_fn` → Function root (parent = CallFunction)
 * - `/do/0/calc/_fn/do` → Function's do block (parent = function root)
 * - `/do/0/calc/_fn/do/0/step1` → Target task (parent = do block)
 *
 * For nested function calls like `/do/0/calc/_fn/do/1/recurse/_fn/do/0/base`:
 * - Each `_fn` segment introduces a new function context
 * - The parent chain is built through all function boundaries
 *
 * Returns a NEW node with:
 * - Full composite position (the one passed in)
 * - Correct parent references for navigation
 */
private fun Workflow.resolveNodeThroughFunctions(
    position: NodePosition,
    functionResolver: suspend (String) -> Task
): Node<*> {
    val fnToken = "/${Token.FUN.token}"
    val path = position.toString()

    // First, get the CallFunction node from the workflow tree
    val callFunctionPosition = path.substringBefore(fnToken)
    val workflowNodesMap = WorkflowCache.getNodesMap(this)
    var currentParent: Node<*> = workflowNodesMap[NodePosition(callFunctionPosition)]
        ?: throw IllegalStateException(
            "Node not found at position $callFunctionPosition in workflow: ${this.namespace}/${this.name}/${this.version}"
        )

    // The remaining path after the CallFunction position
    var remainingPath = path.substring(callFunctionPosition.length)

    // Process each _fn segment
    while (remainingPath.startsWith(fnToken)) {
        // Current parent should be a CallFunction
        require(currentParent.task is CallFunction) {
            "Expected CallFunction at ${currentParent.position}, found ${currentParent.task::class.simpleName}"
        }

        val functionRef = currentParent.task.call

        // Resolve the function task
        val functionNodesMap = try {
            val functionTask = this.useFunctions?.get(functionRef) ?: runBlocking { functionResolver(functionRef) }
            WorkflowCache.functionNodes(this, functionTask, functionRef, currentParent.extensionOwner != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NodeResolutionException(currentParent, e)
        }

        // Find the next _fn boundary (or end of path)
        val afterFn = remainingPath.substring(fnToken.length) // Skip the /_fn prefix
        val nextFnIndex = afterFn.indexOf(fnToken)
        val currentFnPath = if (nextFnIndex >= 0) {
            afterFn.take(nextFnIndex)
        } else {
            afterFn
        }

        // Build the parent chain within this function
        currentParent = buildParentChain(
            functionNodesMap = functionNodesMap,
            targetPath = currentFnPath,
            callFunctionNode = currentParent,
            fullPositionPrefix = currentParent.position.toString() + fnToken
        )

        // Update remaining path
        remainingPath = if (nextFnIndex >= 0) {
            afterFn.substring(nextFnIndex)
        } else {
            ""
        }
    }

    return currentParent
}

/**
 * Builds the parent chain from the CallFunction down to the target node within a function.
 *
 * For example, if targetPath is "/do/0/step1", this builds:
 * - /_fn/do with parent = callFunctionNode (skip function root)
 * - /_fn/do/0/step1 with parent = do block
 *
 * Note: We skip the function root (/_fn) because:
 * 1. It's never pushed to the stack (we navigate directly to /_fn/do)
 * 2. When the function's do block completes, it should return to CallFunction
 *
 * Intermediate positions like `/_fn/do/0` may not exist as nodes
 * (positions include task names, e.g., `/_fn/do/0/step1`). We skip
 * segments that don't correspond to actual nodes.
 */
private fun buildParentChain(
    functionNodesMap: Map<NodePosition, Node<*>>,
    targetPath: String,
    callFunctionNode: Node<*>,
    fullPositionPrefix: String
): Node<*> {
    val fnToken = Token.FUN.token

    // Skip the function root - it's not in the stack and would cause issues
    // when DoProcessor returns to it. Instead, we make the do block's parent
    // be the CallFunction directly.

    // If target is just the function root or empty, we have a problem
    // because we're skipping the root
    if (targetPath.isEmpty() || targetPath == "/") {
        throw IllegalStateException(
            "Cannot navigate to function root directly. Navigate to /_fn/do instead."
        )
    }

    // Build chain by accumulating path segments and checking for valid nodes
    val pathSegments = targetPath.split("/").filter { it.isNotEmpty() }
    var currentPath = "/$fnToken"
    var currentNode: Node<*> = callFunctionNode  // Start with CallFunction as parent

    for (segment in pathSegments) {
        currentPath = "$currentPath/$segment"
        val cachedNode = functionNodesMap[NodePosition(currentPath)]

        // Only add to chain if this position has an actual node
        // Intermediate positions like /_fn/do/0 may not exist
        if (cachedNode != null) {
            // Remove /_fn/ prefix, leaving e.g., "do/0/step" from "/_fn/do/0/step"
            val relativePath = currentPath.removePrefix("/$fnToken/")
            // fullPositionPrefix ends with /_fn, add / before relative path
            val fullPosition = NodePosition("$fullPositionPrefix/$relativePath")
            currentNode = Node(
                position = fullPosition,
                task = cachedNode.task,
                name = cachedNode.name,
                parent = currentNode,
                origin = if (callFunctionNode.extensionOwner != null) NodeOrigin.EXTENSION_DESCENDANT else cachedNode.origin,
            )
        }
    }

    // Verify we found the target
    val targetFullPath = "/$fnToken$targetPath"
    if (functionNodesMap[NodePosition(targetFullPath)] == null) {
        throw IllegalStateException("Target node not found at $targetFullPath in function tree")
    }

    return currentNode
}
