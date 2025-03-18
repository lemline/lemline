package com.lemline.swruntime.tasks.flows

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.DoTask

open class DoInstance(
    override val node: NodeTask<DoTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<DoTask>(node, parent) {

    override fun `continue`(): NodeInstance<*>? {
        childIndex++

        return when (childIndex) {
            0 -> children[0].also { it.rawInput = transformedInput }
            children.size -> then()
            else -> children[childIndex].also { it.rawInput = rawOutput }
        }
    }

    override fun setState(state: NodeState) {
        childIndex = state.getIndex()
    }

    override fun getState() = when (childIndex >= 0) {
        true -> NodeState().apply { setIndex(childIndex) }
        else -> null
    }
}
