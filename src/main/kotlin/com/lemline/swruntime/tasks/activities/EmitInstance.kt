package com.lemline.swruntime.tasks.activities

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.EmitTask

class EmitInstance(
    override val node: Node<EmitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<EmitTask>(node, parent) {
    private var eventId: String? = null
    private var status: String? = null

    override fun setState(state: NodeState) {
        eventId = scope[EVENT_ID]?.asText()
        status = scope[STATUS]?.asText()
    }

    override fun getState() = NodeState().apply {
        eventId?.let { this[EVENT_ID] = JsonNodeFactory.instance.textNode(it) }
        status?.let { this[STATUS] = JsonNodeFactory.instance.textNode(it) }
    }

    companion object {
        private const val EVENT_ID = "event.id"
        private const val STATUS = "status"
    }
} 