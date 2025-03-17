package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.CallHTTP

class CallHttpInstance(
    override val node: Node<CallHTTP>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallHTTP>(node, parent) {
    private var status: Int? = null
    private var error: String? = null

    override fun setState(scope: NodeState) {
        status = scope[STATUS]?.asInt()
        error = scope[ERROR]?.asText()
    }

    override fun getState() = NodeState().apply {
        status?.let { this[STATUS] = JsonNodeFactory.instance.numberNode(it) }
        error?.let { this[ERROR] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val STATUS = "status"
        private const val ERROR = "error"
    }
} 