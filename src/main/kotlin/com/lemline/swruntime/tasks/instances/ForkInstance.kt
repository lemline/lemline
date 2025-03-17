package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.ForkTask

class ForkInstance(
    override val node: Node<ForkTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ForkTask>(node, parent) {
    private var branchIndex: Int? = null
    private var taskIndex: Int? = null

    override fun setState(scope: NodeState) {
        branchIndex = scope[BRANCH_INDEX]?.asInt()
        taskIndex = scope[TASK_INDEX]?.asInt()
    }

    override fun getState() = NodeState().apply {
        branchIndex?.let { this[BRANCH_INDEX] = JsonNodeFactory.instance.numberNode(it) }
        taskIndex?.let { this[TASK_INDEX] = JsonNodeFactory.instance.numberNode(it) }
    }

    companion object {
        private const val BRANCH_INDEX = "branch.childIndex"
        private const val TASK_INDEX = "task.childIndex"
    }
} 