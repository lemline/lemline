package com.lemline.swruntime.tasks.flows

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.RaiseTask

class RaiseInstance(
    override val node: NodeTask<RaiseTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<RaiseTask>(node, parent) {
    private var error: String? = null
    

    companion object {
        private const val ERROR = "error"
    }
} 