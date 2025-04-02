package com.lemline.sw.nodes.flows

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.ListenTask

class ListenInstance(
    override val node: Node<ListenTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ListenTask>(node, parent) {
    private var eventCount: Int? = null
    private var timeout: Long? = null
    private var status: String? = null

    companion object {
        private const val EVENT_COUNT = "event.count"
        private const val TIMEOUT = "timeout"
        private const val STATUS = "status"
    }
} 