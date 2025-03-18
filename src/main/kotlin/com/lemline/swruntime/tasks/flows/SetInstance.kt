package com.lemline.swruntime.tasks.flows

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.SetTask

class SetInstance(
    override val node: NodeTask<SetTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<SetTask>(node, parent) {
    private var variables: Map<String, String>? = null

    override fun setState(state: NodeState) {
//        variables = scope[VARIABLES]?.fields()?.asSequence()?.map { it.key to it.value.asText() }?.toMap()
    }

    override fun getState() = NodeState().apply {
//        variables?.let { vars ->
//            this[VARIABLES] = JsonNodeFactory.instance.objectNode().apply {
//                vars.forEach { (key, value) -> put(key, value) }
//            }
//        }
    }

    companion object {
//        private const val VARIABLES = "variables"
    }
} 