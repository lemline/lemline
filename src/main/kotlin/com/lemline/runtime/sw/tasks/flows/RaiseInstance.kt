package com.lemline.runtime.sw.tasks.flows

import com.lemline.runtime.sw.tasks.NodeInstance
import com.lemline.runtime.sw.tasks.NodeTask
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