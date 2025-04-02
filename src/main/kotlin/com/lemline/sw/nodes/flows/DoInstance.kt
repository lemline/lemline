package com.lemline.sw.nodes.flows

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.DoTask

open class DoInstance(
    override val node: Node<DoTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<DoTask>(node, parent) {

    override fun `continue`(): NodeInstance<*>? {
        childIndex++

        return when (childIndex) {
            children.size -> then()
            else -> children[childIndex].also { it.rawInput = rawOutput!! }
        }
    }
}
