package com.lemline.swruntime.tasks.activities

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.CallOpenAPI

class CallOpenApiInstance(
    override val node: NodeTask<CallOpenAPI>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallOpenAPI>(node, parent) {
    private var status: Int? = null
    private var error: String? = null

    override fun setState(state: NodeState) {
//        status = scope[STATUS]?.asInt()
//        error = scope[ERROR]?.asText()
    }

    override fun getState() = NodeState().apply {
//        status?.let { this[STATUS] = JsonNodeFactory.instance.numberNode(it) }
//        error?.let { this[ERROR] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val STATUS = "status"
        private const val ERROR = "error"
    }
} 