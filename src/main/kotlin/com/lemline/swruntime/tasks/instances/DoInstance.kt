package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.DoTask

open class DoInstance(
    override val node: Node<DoTask>,
    override val parent: NodeInstance<*>?,
) : NodeInstance<DoTask>(node, parent) {

    override fun shouldRun(rawInput: JsonNode): Boolean = when (childIndex) {
        null -> super.shouldRun(rawInput)
        else -> true
    }

    override fun `continue`(): NodeInstance<*>? {
        childIndex = when (childIndex) {
            null -> 0
            else -> childIndex!! + 1
        }
        return when (childIndex) {
            children.size -> parent
            else -> children[childIndex!!]
        }
    }

    override fun setState(scope: NodeState) {
        childIndex = scope[INDEX]?.asInt()
    }

    override fun getState() = childIndex?.let {
        NodeState().apply { set(INDEX, JsonNodeFactory.instance.numberNode(it)) }
    }

    companion object {
        private const val INDEX = "index"
    }
}
