package com.lemline.swruntime.tasks.flows

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.TryTask

class TryInstance(
    override val node: NodeTask<TryTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<TryTask>(node, parent) {
    private var index: Int? = null
    private var error: String? = null

    override fun setState(state: NodeState) {
//        index = scope[INDEX]?.asInt()
//        error = scope[ERROR]?.asText()
    }

    override fun getState() = NodeState().apply {
//        index?.let { this[INDEX] = JsonNodeFactory.instance.numberNode(it) }
//        error?.let { this[ERROR] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
//        private const val INDEX = "childIndex"
//        private const val ERROR = "error"
    }
} 