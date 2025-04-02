package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.EmitTask

class EmitInstance(
    override val node: Node<EmitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<EmitTask>(node, parent) {
    private var eventId: String? = null
    private var status: String? = null

    companion object {
        private const val EVENT_ID = "event.id"
        private const val STATUS = "status"
    }
} 