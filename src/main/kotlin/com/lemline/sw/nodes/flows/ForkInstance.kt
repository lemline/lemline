package com.lemline.sw.nodes.flows

import com.lemline.sw.nodes.NodeInstance
import com.lemline.sw.nodes.NodeTask
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