package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.SwitchTask

class SwitchInstance(
    override val node: Node<SwitchTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<SwitchTask>(node, parent) {
    private var selectedCase: String? = null
    private var index: Int? = null

    override fun setState(scope: NodeState) {
        selectedCase = scope[SELECTED_CASE]?.asText()
        index = scope[INDEX]?.asInt()
    }

    override fun getState() = NodeState().apply {
        selectedCase?.let { this[SELECTED_CASE] = JsonNodeFactory.instance.textNode(it) }
        index?.let { this[INDEX] = JsonNodeFactory.instance.numberNode(it) }
    }

    companion object {
        private const val SELECTED_CASE = "selected.case"
        private const val INDEX = "childIndex"
    }
} 