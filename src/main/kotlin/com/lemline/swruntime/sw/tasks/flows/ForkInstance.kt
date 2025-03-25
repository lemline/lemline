package com.lemline.swruntime.sw.tasks.flows

import com.lemline.swruntime.sw.tasks.NodeInstance
import com.lemline.swruntime.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.ForkTask

class ForkInstance(
    override val node: NodeTask<ForkTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ForkTask>(node, parent) {
    private var branchIndex: Int? = null
    private var taskIndex: Int? = null


    companion object {
        private const val BRANCH_INDEX = "branch.childIndex"
        private const val TASK_INDEX = "task.childIndex"
    }
} 