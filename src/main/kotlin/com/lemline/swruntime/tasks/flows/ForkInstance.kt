package com.lemline.swruntime.tasks.flows

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
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