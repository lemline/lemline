package com.lemline.sw.nodes.flows

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.RaiseTask

class RaiseInstance(
    override val node: Node<RaiseTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<RaiseTask>(node, parent) {
    private var error: String? = null


    companion object {
        private const val ERROR = "error"
    }
} 