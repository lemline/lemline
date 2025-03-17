package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.ListenTask

class ListenInstance(
    override val node: Node<ListenTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ListenTask>(node, parent) {
    private var eventCount: Int? = null
    private var timeout: Long? = null
    private var status: String? = null

    override fun setState(scope: NodeState) {
        eventCount = scope[EVENT_COUNT]?.asInt()
        timeout = scope[TIMEOUT]?.asLong()
        status = scope[STATUS]?.asText()
    }

    override fun getState() = NodeState().apply {
        eventCount?.let { this[EVENT_COUNT] = JsonNodeFactory.instance.numberNode(it) }
        timeout?.let { this[TIMEOUT] = JsonNodeFactory.instance.numberNode(it) }
        status?.let { this[STATUS] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val EVENT_COUNT = "event.count"
        private const val TIMEOUT = "timeout"
        private const val STATUS = "status"
    }
} 