package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.NodeInstance
import com.lemline.sw.nodes.NodeTask
import io.serverlessworkflow.api.types.CallAsyncAPI

class CallAsyncApiInstance(
    override val node: NodeTask<CallAsyncAPI>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallAsyncAPI>(node, parent) {
    private var correlationId: String? = null
    private var status: String? = null

    companion object {
        private const val CORRELATION_ID = "correlation.id"
        private const val STATUS = "status"
    }
} 