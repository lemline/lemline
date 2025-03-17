package com.lemline.swruntime.tasks.activities

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import io.serverlessworkflow.api.types.RunTask

class RunInstance(
    override val node: Node<RunTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<RunTask>(node, parent) {
    private var processId: String? = null
    private var status: String? = null
    private var exitCode: Int? = null

    override fun setState(state: NodeState) {
        processId = scope[PROCESS_ID]?.asText()
        status = scope[STATUS]?.asText()
        exitCode = scope[EXIT_CODE]?.asInt()
    }

    override fun getState() = NodeState().apply {
        processId?.let { this[PROCESS_ID] = JsonNodeFactory.instance.textNode(it) }
        status?.let { this[STATUS] = JsonNodeFactory.instance.textNode(it) }
        exitCode?.let { this[EXIT_CODE] = JsonNodeFactory.instance.numberNode(it) }
    }

    companion object {
        private const val PROCESS_ID = "process.id"
        private const val STATUS = "status"
        private const val EXIT_CODE = "exit.code"
    }
} 