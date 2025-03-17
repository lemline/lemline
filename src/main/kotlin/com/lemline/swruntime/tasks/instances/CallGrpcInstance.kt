package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.CallGRPC

class CallGrpcInstance(
    override val node: Node<CallGRPC>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallGRPC>(node, parent) {
    private var status: String? = null
    private var error: String? = null

    override fun setState(scope: NodeState) {
        status = scope[STATUS]?.asText()
        error = scope[ERROR]?.asText()
    }

    override fun getState() = NodeState().apply {
        status?.let { this[STATUS] = JsonNodeFactory.instance.textNode(it) }
        error?.let { this[ERROR] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val STATUS = "status"
        private const val ERROR = "error"
    }
} 