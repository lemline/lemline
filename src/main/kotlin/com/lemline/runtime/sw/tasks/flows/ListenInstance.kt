package com.lemline.runtime.sw.tasks.flows

import com.lemline.runtime.sw.tasks.NodeInstance
import com.lemline.runtime.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.ListenTask

class ListenInstance(
    override val node: NodeTask<ListenTask>,
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