package com.lemline.runtime.sw.tasks.flows

import com.lemline.runtime.sw.tasks.NodeInstance
import com.lemline.runtime.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.DoTask

open class DoInstance(
    override val node: NodeTask<DoTask>,
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
