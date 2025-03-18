package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.ForTask

class ForInstance(
    override val node: NodeTask<ForTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ForTask>(node, parent) {

    private var index: Int? = null
    private var forIn: List<JsonNode>? = null

    override fun setState(state: NodeState) {
//        forIn = scope[FOR_IN]?.toList()
//        index = scope[INDEX]?.asInt()
    }

    override fun getState() = NodeState().apply {
//        forIn?.let { this[FOR_IN] = JsonNodeFactory.instance.arrayNode().addAll(it) }
//        index?.let { this[INDEX] = JsonNodeFactory.instance.numberNode(it) }
    }

    companion object {
        private const val INDEX = "childIndex"
        private const val ITEM = "item"
        private const val FOR_EACH = "for.each"
        private const val FOR_IN = "for.in"
        private const val FOR_AT = "for.at"
    }
}
