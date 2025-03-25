package com.lemline.swruntime.sw.tasks.activities

import com.lemline.swruntime.sw.errors.WaitWorkflowException
import com.lemline.swruntime.sw.tasks.NodeInstance
import com.lemline.swruntime.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.WaitTask

class WaitInstance(
    override val node: NodeTask<WaitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<WaitTask>(node, parent) {

    override suspend fun execute() {
        throw WaitWorkflowException()
    }
} 