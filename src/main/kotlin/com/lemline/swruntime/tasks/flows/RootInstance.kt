package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.Scope
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.RootTask
import io.serverlessworkflow.impl.json.JsonUtils

class RootInstance(
    override val node: Node<RootTask>,
) : NodeInstance<RootTask>(node, null) {

    lateinit var context: ObjectNode
    lateinit var secrets: Map<String, JsonNode>
    lateinit var workflowDescriptor: WorkflowDescriptor
    lateinit var runtimeDescriptor: RuntimeDescriptor

    override val scope: ObjectNode
        get() = Scope().apply {
            setContext(context)
            setSecrets(secrets)
            setWorkflow(workflowDescriptor)
            setRuntime(runtimeDescriptor)
        }.toJson()

    override fun setContext(context: ObjectNode) {
        this.context = context
    }

    override fun setState(state: NodeState) {
        // set index
        super.setState(state)
        // set context
        context = state.getContext() ?: JsonUtils.`object`()
        // secrets, workflowDescriptor, runtimeDescriptor are set from WorkflowInstance
    }

    override fun getState(): NodeState = (super.getState() ?: NodeState()).apply {
        if (!context.isEmpty) this.setContext(context)
        setId(workflowDescriptor.id)
        setRawInput(workflowDescriptor.input)
        setStartedAt(workflowDescriptor.startedAt)
    }

    override fun `continue`(): NodeInstance<*>? {
        childIndex = when (childIndex) {
            null -> 0
            else -> 1
        }
        return when (childIndex) {
            0 -> children[0]
            else -> null
        }
    }
}
