package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallGRPC

class CallGrpcInstance(
    override val node: Node<CallGRPC>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallGRPC>(node, parent) {
    private var status: String? = null
    private var error: String? = null

    companion object {
        private const val STATUS = "status"
        private const val ERROR = "error"
    }
} 