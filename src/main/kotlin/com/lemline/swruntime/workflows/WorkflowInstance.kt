package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.tasks.*
import com.lemline.swruntime.tasks.activities.*
import com.lemline.swruntime.tasks.flows.*
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import jakarta.inject.Inject

/**
 * Represents an instance of a workflow.
 *
 * @property name The name of the workflow.
 * @property version The version of the workflow.
 * @property id The unique identifier of the workflow instance.
 * @property rawInput The raw input data for the instance as a JSON node.
 *
 */
class WorkflowInstance(
    val name: String,
    val version: String,
    val state: MutableMap<NodePosition, NodeState>,
    var position: NodePosition
) {
    @Inject
    internal lateinit var workflowService: WorkflowService

    /**
     * Instance ID
     * (parsed from the state)
     */
    private val id: String

    /**
     * Instance starting date
     * (parsed from the state)
     */
    private val startedAt: DateTimeDescriptor

    /**
     * Instance raw input
     * (parsed from the state)
     */
    private val rawInput: JsonNode

    init {
        val rootState = state[NodePosition.root] ?: error("no state provided for the root node")

        this.id = rootState.getId()
        this.startedAt = rootState.getStartedAt()
        this.rawInput = rootState.getRawInput()!!
    }

    // Retrieves the workflow by its name and version
    private val workflow by lazy { workflowService.getWorkflow(name, version) }

    internal val nodeInstances: Map<NodePosition, NodeInstance<*>> by lazy { initInstance() }

    internal lateinit var rootInstance: RootInstance

    suspend fun run() {

        // Get the task at the current position
        val current = nodeInstances[position] ?: error("task not found in position $position")

        // Complete the current activity execution (rawOutput should be part of the state)
        var next = when (isStarting()) {
            true -> {
                current.onEnter()
                current.`continue`()
            }

            false -> current.then()
        }

        // find and execute the next activity
        while (true) {
            // if null, then the workflow is completed
            if (next == null) break
            // Get transformed Input for this node
            if (next.shouldEnter()) {
                // if next is an activity, then break
                if (next.node.isActivity()) break
            }
            next = next.`continue`()
        }
        // next is not null, only if it's an activity
        position = next?.node?.position ?: NodePosition.root

        next?.let { it.rawOutput = it.execute() }
    }

    private fun isStarting(): Boolean = position == NodePosition.root && rootInstance.childIndex == -1

    internal fun isCompleted(): Boolean = position == NodePosition.root && rootInstance.childIndex == 1

    /**
     * Retrieves the task instances of the given workflow with the provided state.
     *
     * This method initializes the root instance with the provided state,
     * and then recursively populates the instance node and its children with the state.
     *
     * @return A map of task positions to their task instances.
     */
    private fun initInstance(): Map<NodePosition, NodeInstance<*>> {
        val rootNode = workflowService.getRootNode(workflow)
        rootInstance = rootNode.createInstance(null) as RootInstance
        // reinit for this execution
        rootInstance.secrets = workflowService.getSecrets(workflow)
        rootInstance.runtimeDescriptor = RuntimeDescriptor
        rootInstance.workflowDescriptor = WorkflowDescriptor(
            id = id,
            definition = workflow,
            input = rawInput,
            startedAt = startedAt
        )

        // create the map<NodePosition, NodeInstance<*>>
        val nodeInstances = mutableMapOf<NodePosition, NodeInstance<*>>()
        fun collect(nodeInstance: NodeInstance<*>) {
            nodeInstances[nodeInstance.node.position] = nodeInstance
            nodeInstance.children.forEach { collect(it) }
        }
        collect(rootInstance)

        return nodeInstances
    }

    internal fun getState(): MutableMap<NodePosition, NodeState> {
        val state = mutableMapOf<NodePosition, NodeState>()
        fun collect(nodeInstance: NodeInstance<*>) {
            nodeInstance.getState()?.let { state[nodeInstance.node.position] = it }
            nodeInstance.children.forEach { collect(it) }
        }
        collect(rootInstance)

        return state
    }

    @Suppress("UNCHECKED_CAST")
    private fun NodeTask<*>.createInstance(parent: NodeInstance<*>?): NodeInstance<*> = when (task) {
        is RootTask -> RootInstance(this as NodeTask<RootTask>)
        is DoTask -> DoInstance(this as NodeTask<DoTask>, parent!!)
        is ForTask -> ForInstance(this as NodeTask<ForTask>, parent!!)
        is TryTask -> TryInstance(this as NodeTask<TryTask>, parent!!)
        is ForkTask -> ForkInstance(this as NodeTask<ForkTask>, parent!!)
        is RaiseTask -> RaiseInstance(this as NodeTask<RaiseTask>, parent!!)
        is SetTask -> SetInstance(this as NodeTask<SetTask>, parent!!)
        is SwitchTask -> SwitchInstance(this as NodeTask<SwitchTask>, parent!!)
        is CallAsyncAPI -> CallAsyncApiInstance(this as NodeTask<CallAsyncAPI>, parent!!)
        is CallGRPC -> CallGrpcInstance(this as NodeTask<CallGRPC>, parent!!)
        is CallHTTP -> CallHttpInstance(this as NodeTask<CallHTTP>, parent!!)
        is CallOpenAPI -> CallOpenApiInstance(this as NodeTask<CallOpenAPI>, parent!!)
        is EmitTask -> EmitInstance(this as NodeTask<EmitTask>, parent!!)
        is ListenTask -> ListenInstance(this as NodeTask<ListenTask>, parent!!)
        is RunTask -> RunInstance(this as NodeTask<RunTask>, parent!!)
        is WaitTask -> WaitInstance(this as NodeTask<WaitTask>, parent!!)
        else -> throw IllegalArgumentException("Unknown task type: ${task.javaClass.name}")
    }
        // apply state for this new node instance
        .apply { state[node.position]?.let { setState(it) } }
        // create all children node instances
        .also { nodeInstance ->
            nodeInstance.children = this.children?.map { child -> child.createInstance(nodeInstance) } ?: emptyList()
        }

    private fun error(message: Any): Nothing =
        throw IllegalStateException("Workflow $name (version $version): $message")

}