package com.lemline.swruntime.tasks.activities

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.WaitTask

class WaitInstance(
    override val node: NodeTask<WaitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<WaitTask>(node, parent) {
    private var startTime: Long? = null
    private var endTime: Long? = null
    private var status: String? = null

    companion object {
        private const val START_TIME = "start.time"
        private const val END_TIME = "end.time"
        private const val STATUS = "status"
    }
} 