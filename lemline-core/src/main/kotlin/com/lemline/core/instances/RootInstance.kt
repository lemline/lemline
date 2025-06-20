// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.instances

import com.lemline.core.RuntimeDescriptor
import com.lemline.core.expressions.scopes.Scope
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.NodeState
import com.lemline.core.nodes.RootTask
import io.serverlessworkflow.api.types.RetryPolicy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class RootInstance(override val node: Node<RootTask>) : NodeInstance<RootTask>(node, null) {

    internal var context: JsonObject
        get() = state.context
        set(value) {
            state.context = value
        }

    lateinit var secrets: Map<String, JsonElement>
    lateinit var workflowDescriptor: WorkflowDescriptor
    lateinit var runtimeDescriptor: RuntimeDescriptor

    override val scope: JsonObject
        get() = Scope(
            context = context,
            secrets = secrets,
            workflow = workflowDescriptor,
            runtime = runtimeDescriptor,
        ).toJsonObject()

    override fun `continue`(): NodeInstance<*>? {
        childIndex++

        return when (childIndex) {
            0 -> children[0].also { it.rawInput = transformedInput }
            else -> null
        }
    }

    override fun reset() {
        state.childIndex = NodeState.CHILD_INDEX_DEFAULT
    }

    fun getRetryPolicyByName(name: String): RetryPolicy = node.task.use?.retries?.additionalProperties
        ?.get(name)
        ?: error("Unknown retry policy name '$name'")
}
