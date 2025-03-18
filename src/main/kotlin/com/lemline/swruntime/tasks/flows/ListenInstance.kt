package com.lemline.swruntime.tasks.flows

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.ListenTask

class ListenInstance(
    override val node: NodeTask<ListenTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ListenTask>(node, parent) {
    private var eventCount: Int? = null
    private var timeout: Long? = null
    private var status: String? = null

    override fun setState(state: NodeState) {
//        eventCount = state[EVENT_COUNT]?.asInt()
//        timeout = state[TIMEOUT]?.asLong()
//        status = state[STATUS]?.asText()
    }

    override fun getState() = NodeState().apply {
//        eventCount?.let { this[EVENT_COUNT] = JsonNodeFactory.instance.numberNode(it) }
//        timeout?.let { this[TIMEOUT] = JsonNodeFactory.instance.numberNode(it) }
//        status?.let { this[STATUS] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val EVENT_COUNT = "event.count"
        private const val TIMEOUT = "timeout"
        private const val STATUS = "status"
    }
} 