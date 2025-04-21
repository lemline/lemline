// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.flows

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.DoTask

open class DoInstance(override val node: Node<DoTask>, override val parent: NodeInstance<*>) :
    NodeInstance<DoTask>(node, parent) {

    override fun `continue`(): NodeInstance<*>? {
        childIndex++

        return when (childIndex) {
            children.size -> then()
            else -> children[childIndex].also { it.rawInput = rawOutput!! }
        }
    }
}
