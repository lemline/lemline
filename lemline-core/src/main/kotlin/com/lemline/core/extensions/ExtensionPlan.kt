// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.extensions

import com.lemline.common.values.Token
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeOrigin
import com.lemline.core.nodes.RootTask
import io.serverlessworkflow.api.types.CallAsyncAPI
import io.serverlessworkflow.api.types.CallFunction
import io.serverlessworkflow.api.types.CallGRPC
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.CallOpenAPI
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.EmitTask
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.RaiseTask
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SwitchTask
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.WaitTask

data class ExtensionPlan(
    val index: Int,
    val name: String,
    val condition: String?,
    val before: Node<DoTask>?,
    val after: Node<DoTask>?,
)

/** Called while indexing a definition, and when lazily compiling a function for that definition. */
fun compileExtensions(node: Node<*>): List<ExtensionPlan> {
    if (node.origin != NodeOrigin.DECLARED || generateSequence(node) { it.parent }.any {
        it.origin == NodeOrigin.EXTENSION_BLOCK || it.origin == NodeOrigin.EXTENSION_DESCENDANT
    }) return emptyList()
    val root = generateSequence(node) { it.parent }.last().task as? RootTask ?: return emptyList()
    return root.use?.extensions.orEmpty().mapIndexedNotNull { index, item ->
        val extension = requireNotNull(item.extension) { "Missing extension '${item.name}'" }
        val target = requireNotNull(extension.extend) { "Missing extend in '${item.name}'" }.toString()
        val type = when (node.task) {
            is DoTask, is ForkTask -> "composite"
            is CallFunction, is CallHTTP, is CallGRPC, is CallOpenAPI, is CallAsyncAPI -> "call"
            is ForTask -> "for"
            is TryTask -> "try"
            is SetTask -> "set"
            is SwitchTask -> "switch"
            is WaitTask -> "wait"
            is ListenTask -> "listen"
            is RunTask -> "run"
            is EmitTask -> "emit"
            is RaiseTask -> "raise"
            else -> return@mapIndexedNotNull null
        }
        if (target != "all" && target != type) return@mapIndexedNotNull null
        fun block(phase: String, tasks: List<TaskItem>?): Node<DoTask>? = tasks?.takeIf { it.isNotEmpty() }?.let {
            Node(
                node.position.addToken(Token.EXT).addIndex(index).addName(phase).addToken(Token.DO),
                DoTask().withDo(it), item.name, node, NodeOrigin.EXTENSION_BLOCK,
            )
        }
        ExtensionPlan(index, item.name, extension.`when`, block("before", extension.before), block("after", extension.after))
    }
}
