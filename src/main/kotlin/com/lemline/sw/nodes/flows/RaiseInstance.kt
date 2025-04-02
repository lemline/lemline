package com.lemline.sw.nodes.flows

import com.lemline.sw.nodes.NodeInstance
import com.lemline.sw.nodes.NodeTask
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