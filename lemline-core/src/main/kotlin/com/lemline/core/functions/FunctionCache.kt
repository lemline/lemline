// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.functions

import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token.FUN
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.workflows.WorkflowParser
import io.serverlessworkflow.api.types.CallTask
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.Document
import io.serverlessworkflow.api.types.Task
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.TestOnly

/**
 * Cache for function node trees.
 *
 * Function node trees are built lazily when a function is first invoked by any workflow instance.
 * Unlike workflow trees which are built at parse time, function trees are built on-demand
 * because:
 * 1. Recursive functions would cause infinite tree expansion at parse time
 * 2. Remote functions need to be fetched before their tree can be built
 *
 * ## Position Scheme
 *
 * Function nodes use the `_fn` token to mark the function body:
 * ```
 * /do/0/callFunc/_fn/do/0/step1
 *                 ^-- function body marker
 * ```
 *
 * The function tree itself is rooted at the `_fn` token:
 * - `/_fn` - function root
 * - `/_fn/do` - function's do block
 * - `/_fn/do/0/taskName` - first task in function
 *
 * ## Cache Key
 *
 * Functions are cached by:
 * - Named functions: `(workflowInfo, functionName)` - scoped to parent workflow
 * - Remote functions: `(null, functionRef)` - globally unique by URL/catalog ref
 */
object FunctionCache {

    /**
     * Cache key for function trees.
     * Named functions are scoped to their parent workflow, remote functions are global.
     */
    private data class FunctionKey(
        val parentWorkflow: WorkflowInfo?,
        val functionRef: String
    )

    private val nodesMapCache = ConcurrentHashMap<FunctionKey, Map<NodePosition, Node<*>>>()

    /**
     * Gets or builds the node tree for a function.
     *
     * @param task The function's task definition
     * @param functionRef The function reference (name for named, URL/catalog ref for remote)
     * @param parentWorkflowInfo The parent workflow info (null for remote functions)
     * @return Map of positions to nodes for this function
     */
    fun getOrBuild(
        task: Task,
        functionRef: String,
        parentWorkflowInfo: WorkflowInfo? = null
    ): Map<NodePosition, Node<*>> {
        val key = FunctionKey(parentWorkflowInfo, functionRef)
        return nodesMapCache.getOrPut(key) {
            buildFunctionNodesMap(task)
        }
    }

    /**
     * Builds a node map for a function task.
     *
     * The function is wrapped in a root structure:
     * ```
     * /_fn (root)
     * /_fn/do (do block containing function tasks)
     * /_fn/do/0/taskName (first task)
     * ```
     */
    private fun buildFunctionNodesMap(task: Task): Map<NodePosition, Node<*>> {
        val taskBase = task.toTaskBase()

        // Create a synthetic root for the function at /_fn position
        val fnRootPosition = NodePosition.root.addToken(FUN)

        // Wrap the task in a DoTask if it's not already one
        val doTask = taskBase as? DoTask ?: DoTask(listOf(TaskItem("functionTask", task)))

        // Create the function's root node (analogous to workflow root)
        val fnRootTask = RootTask(
            Document().apply {
                dsl = "1.0.0"
                namespace = "_function"
                name = "inline"
                version = "1.0.0"
            },
            doTask.`do`,
            null
        )

        val fnRootNode = Node(
            position = fnRootPosition,
            task = fnRootTask,
            name = "$FUN",
            parent = null  // Parent will be set during position resolution
        )

        return WorkflowParser.buildNodesMap(fnRootNode)
    }

    /**
     * Converts a Task union type to its underlying TaskBase.
     */
    private fun Task.toTaskBase(): TaskBase = when {
        doTask != null -> doTask
        setTask != null -> setTask
        callTask != null -> when (val call = callTask.get()) {
            is TaskBase -> call
            is CallTask -> call.get() as TaskBase
            else -> throw IllegalArgumentException("Unsupported call type: ${call?.javaClass?.name}")
        }

        tryTask != null -> tryTask
        forTask != null -> forTask
        forkTask != null -> forkTask
        switchTask != null -> switchTask
        raiseTask != null -> raiseTask
        runTask != null -> runTask
        waitTask != null -> waitTask
        listenTask != null -> listenTask
        emitTask != null -> emitTask
        else -> throw IllegalArgumentException("Task has no recognized task type: $this")
    }

    @TestOnly
    fun clear() {
        nodesMapCache.clear()
    }
}
