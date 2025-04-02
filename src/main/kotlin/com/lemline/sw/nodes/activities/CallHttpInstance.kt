package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallHTTP

class CallHttpInstance(
    override val node: Node<CallHTTP>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallHTTP>(node, parent) {
    private var status: Int? = null
    private var error: String? = null

    companion object {
        private const val STATUS = "status"
        private const val ERROR = "error"
    }
} 