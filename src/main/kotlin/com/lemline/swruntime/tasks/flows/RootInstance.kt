package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.Scope
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeTask
import com.lemline.swruntime.tasks.RootTask
import io.serverlessworkflow.api.types.RetryPolicy

class RootInstance(
    override val node: NodeTask<RootTask>,
) : NodeInstance<RootTask>(node, null) {

    internal var context: ObjectNode
        get() = state.context
        set(value) {
            state.context = value
        }

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
