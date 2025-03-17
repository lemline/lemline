package com.lemline.swruntime.tasks.instances

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.tasks.Node
import com.lemline.swruntime.tasks.NodePosition
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.RootTask
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.json.JsonUtils

class RootInstance(
    override val node: Node<RootTask>,
) : NodeInstance<RootTask>(node, null) {

    lateinit var context: ObjectNode
    lateinit var secrets: Map<String, JsonNode>
    lateinit var workflowDescriptor: WorkflowDescriptor
    lateinit var runtimeDescriptor: RuntimeDescriptor

    override val scope: ObjectNode
        get() = JsonUtils.mapper().createObjectNode().apply {
            set<ObjectNode>("context", JsonUtils.fromValue(context))
            set<JsonNode>("secrets", JsonUtils.fromValue(secrets))
            set<ObjectNode>("workflow", JsonUtils.fromValue(workflowDescriptor))
            set<ObjectNode>("runtime", JsonUtils.fromValue(runtimeDescriptor))
        }

    override fun setContext(context: ObjectNode) {
        this.context = context
    }

    /**
     *
     */
    override fun setState(state: NodeState) {
        // set index
        super.setState(state)
        // set context
        context = state[CONTEXT_STATE_KEY]?.let {
            require(it is ObjectNode) { error("context should be an object, but is $it") }
            it
        } ?: JsonUtils.mapper().createObjectNode()
        // secrets, workflowDescriptor, runtimeDescriptor are set from WorkflowInstance
    }

    override fun getState(): NodeState = (super.getState() ?: NodeState()).apply {
        if (!context.isEmpty) set(CONTEXT_STATE_KEY, context)
        set(WORKFLOW_STATE_KEY, JsonUtils.fromValue(workflowDescriptor).apply { remove("definition") })
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

    companion object {
        const val WORKFLOW_STATE_KEY = "workflow"
        const val CONTEXT_STATE_KEY = "context"
        fun from(node: Node<DoTask>, states: Map<NodePosition, NodeState>) = from(node, null, states) as RootInstance
    }
}

@Suppress("UNCHECKED_CAST")
private fun from(node: Node<*>, parent: NodeInstance<*>?, states: Map<NodePosition, NodeState>): NodeInstance<*> =
    when (node.task) {
        is DoTask -> when (parent) {
            null -> RootInstance(node as Node<RootTask>)
            else -> DoInstance(node as Node<DoTask>, parent)
        }

        is ForTask -> ForInstance(node as Node<ForTask>, parent!!)
        is TryTask -> TryInstance(node as Node<TryTask>, parent!!)
        is ForkTask -> ForkInstance(node as Node<ForkTask>, parent!!)
        is RaiseTask -> RaiseInstance(node as Node<RaiseTask>, parent!!)
        is SetTask -> SetInstance(node as Node<SetTask>, parent!!)
        is SwitchTask -> SwitchInstance(node as Node<SwitchTask>, parent!!)
        is CallAsyncAPI -> CallAsyncApiInstance(node as Node<CallAsyncAPI>, parent!!)
        is CallGRPC -> CallGrpcInstance(node as Node<CallGRPC>, parent!!)
        is CallHTTP -> CallHttpInstance(node as Node<CallHTTP>, parent!!)
        is CallOpenAPI -> CallOpenApiInstance(node as Node<CallOpenAPI>, parent!!)
        is EmitTask -> EmitInstance(node as Node<EmitTask>, parent!!)
        is ListenTask -> ListenInstance(node as Node<ListenTask>, parent!!)
        is RunTask -> RunInstance(node as Node<RunTask>, parent!!)
        is WaitTask -> WaitInstance(node as Node<WaitTask>, parent!!)
        else -> throw IllegalArgumentException("Unknown task type: ${node.task.javaClass.name}")
    }
        .apply { states[node.position]?.let { setState(it) } }
        .also { taskInstance ->
            taskInstance.children = when (val children = node.children) {
                null -> emptyList()
                else -> children.map { child -> from(child, taskInstance, states) }
            }
        }
