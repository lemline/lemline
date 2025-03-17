package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.TryTask

class TryInstance(
    override val node: Node<TryTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<TryTask>(node, parent) {
    private var index: Int? = null
    private var error: String? = null

    override fun setState(scope: NodeState) {
        index = scope[INDEX]?.asInt()
        error = scope[ERROR]?.asText()
    }

    override fun getState() = NodeState().apply {
        index?.let { this[INDEX] = JsonNodeFactory.instance.numberNode(it) }
        error?.let { this[ERROR] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val INDEX = "childIndex"
        private const val ERROR = "error"
    }
} 