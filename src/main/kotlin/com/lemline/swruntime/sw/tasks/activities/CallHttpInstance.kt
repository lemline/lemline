package com.lemline.swruntime.sw.tasks.activities

import com.lemline.swruntime.sw.tasks.NodeInstance
import com.lemline.swruntime.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.CallHTTP

class CallHttpInstance(
    override val node: NodeTask<CallHTTP>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallHTTP>(node, parent) {
    private var status: Int? = null
    private var error: String? = null

    companion object {
        private const val STATUS = "status"
        private const val ERROR = "error"
    }
} 