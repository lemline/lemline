package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.RaiseTask

class RaiseInstance(
    override val node: Node<RaiseTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<RaiseTask>(node, parent) {
    private var error: String? = null

    override fun setState(scope: NodeState) {
        error = scope[ERROR]?.asText()
    }

    override fun getState() = NodeState().apply {
        error?.let { this[ERROR] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val ERROR = "error"
    }
} 