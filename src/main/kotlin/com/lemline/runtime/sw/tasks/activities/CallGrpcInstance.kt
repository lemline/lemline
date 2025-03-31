package com.lemline.runtime.sw.tasks.activities

import com.lemline.runtime.sw.tasks.NodeInstance
import com.lemline.runtime.sw.tasks.NodeTask
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