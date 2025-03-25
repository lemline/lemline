package com.lemline.swruntime.sw.tasks.activities

import com.lemline.swruntime.sw.tasks.NodeInstance
import com.lemline.swruntime.sw.tasks.NodeTask
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