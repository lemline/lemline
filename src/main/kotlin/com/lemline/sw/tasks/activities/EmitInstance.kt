package com.lemline.sw.tasks.activities

import com.lemline.sw.tasks.NodeInstance
import com.lemline.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.EmitTask

class EmitInstance(
    override val node: NodeTask<EmitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<EmitTask>(node, parent) {
    private var eventId: String? = null
    private var status: String? = null

    companion object {
        private const val EVENT_ID = "event.id"
        private const val STATUS = "status"
    }
} 