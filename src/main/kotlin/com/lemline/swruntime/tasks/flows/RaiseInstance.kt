package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.RaiseTask

class RaiseInstance(
    override val node: Node<RaiseTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<RaiseTask>(node, parent) {
    private var error: String? = null

    override fun setState(state: NodeState) {
        error = scope[ERROR]?.asText()
    }

    override fun getState() = NodeState().apply {
        error?.let { this[ERROR] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val ERROR = "error"
    }
} 