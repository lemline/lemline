package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.NodeInstance
import com.lemline.sw.nodes.NodeTask
import io.serverlessworkflow.api.types.CallGRPC

class CallGrpcInstance(
    override val node: NodeTask<CallGRPC>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallGRPC>(node, parent) {
    private var status: String? = null
    private var error: String? = null

    companion object {
        private const val STATUS = "status"
        private const val ERROR = "error"
    }
} 