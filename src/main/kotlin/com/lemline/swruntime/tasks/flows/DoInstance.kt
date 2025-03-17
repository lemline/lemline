package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.DoTask

open class DoInstance(
    override val node: Node<DoTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<DoTask>(node, parent) {

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

    override fun setState(state: NodeState) {
        childIndex = scope[INDEX]?.asInt()
    }

    override fun getState() = childIndex?.let {
        NodeState().apply { set(INDEX, JsonNodeFactory.instance.numberNode(it)) }
    }

    companion object {
        private const val INDEX = "index"
    }
}
