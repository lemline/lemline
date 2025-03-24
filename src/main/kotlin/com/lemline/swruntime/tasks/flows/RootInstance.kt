package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.Scope
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.NodeTask
import com.lemline.swruntime.tasks.RootTask
import io.serverlessworkflow.api.types.RetryPolicy

class RootInstance(
    override val node: NodeTask<RootTask>,
) : NodeInstance<RootTask>(node, null) {

    internal var context: ObjectNode
        get() = state.getContext()
        set(value) = state.setContext(value)

    lateinit var secrets: Map<String, JsonNode>
    lateinit var workflowDescriptor: WorkflowDescriptor
    lateinit var runtimeDescriptor: RuntimeDescriptor

    override val scope: ObjectNode
        get() = Scope().apply {
            this.setContext(context)
            this.setSecrets(secrets)
            this.setWorkflow(workflowDescriptor)
            this.setRuntime(runtimeDescriptor)
        }.toJson()

    override fun setState(state: NodeState) {
        // set index
        super.setState(state)
        // set context
        context = state.getContext()
        // secrets, workflowDescriptor, runtimeDescriptor are set from WorkflowInstance
    }

    override fun getState(): NodeState = (super.getState() ?: NodeState()).apply {
        if (!context.isEmpty) this.setContext(context)
        setWorkflowId(workflowDescriptor.id)
        setRawInput(workflowDescriptor.input)
        setStartedAt(workflowDescriptor.startedAt)
    }

    override fun `continue`(): NodeInstance<*>? {
        childIndex++

        return when (childIndex) {
            0 -> children[0].also { it.rawInput = transformedInput }
            else -> null
        }
    }

    fun getRetryPolicy(name: String): RetryPolicy = node.task.use?.retries?.additionalProperties
        ?.get(name)
        ?: error("Unknown retry policy name '$name'")
}
