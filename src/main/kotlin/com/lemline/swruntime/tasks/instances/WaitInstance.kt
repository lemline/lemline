package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.WaitTask

class WaitInstance(
    override val node: Node<WaitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<WaitTask>(node, parent) {
    private var startTime: Long? = null
    private var endTime: Long? = null
    private var status: String? = null

    override fun setState(scope: NodeState) {
        startTime = scope[START_TIME]?.asLong()
        endTime = scope[END_TIME]?.asLong()
        status = scope[STATUS]?.asText()
    }

    override fun getState() = NodeState().apply {
        startTime?.let { this[START_TIME] = JsonNodeFactory.instance.numberNode(it) }
        endTime?.let { this[END_TIME] = JsonNodeFactory.instance.numberNode(it) }
        status?.let { this[STATUS] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val START_TIME = "start.time"
        private const val END_TIME = "end.time"
        private const val STATUS = "status"
    }
} 