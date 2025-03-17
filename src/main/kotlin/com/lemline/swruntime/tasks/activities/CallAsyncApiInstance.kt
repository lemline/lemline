package com.lemline.swruntime.tasks.activities

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.CallAsyncAPI

class CallAsyncApiInstance(
    override val node: Node<CallAsyncAPI>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallAsyncAPI>(node, parent) {
    private var correlationId: String? = null
    private var status: String? = null

    override fun setState(state: NodeState) {
        correlationId = scope[CORRELATION_ID]?.asText()
        status = scope[STATUS]?.asText()
    }

    override fun getState() = NodeState().apply {
        correlationId?.let { this[CORRELATION_ID] = JsonNodeFactory.instance.textNode(it) }
        status?.let { this[STATUS] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val CORRELATION_ID = "correlation.id"
        private const val STATUS = "status"
    }
} 