package com.lemline.runtime.sw.tasks.activities

import com.lemline.runtime.sw.tasks.NodeInstance
import com.lemline.runtime.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.CallOpenAPI

class CallOpenApiInstance(
    override val node: NodeTask<CallOpenAPI>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallOpenAPI>(node, parent) {
    private var status: Int? = null
    private var error: String? = null

    companion object {
        private const val STATUS = "status"
        private const val ERROR = "error"
    }
} 