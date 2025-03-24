package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.errors.CaughtDoWorkflowException
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.tasks.*
import com.lemline.swruntime.tasks.activities.*
import com.lemline.swruntime.tasks.flows.*
import com.lemline.swruntime.utils.logger
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.WorkflowStatus
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
    val states: MutableMap<NodePosition, NodeState>,
    var position: NodePosition
) {
    @Inject
    internal lateinit var workflowParser: WorkflowParser

    private val logger = logger()

    /**
     * Instance status
     */
    private var status = WorkflowStatus.PENDING

    /**
     * Instance WORKFLOW_ID
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
        val rootState = states[NodePosition.root] ?: error("no state provided for the root node")

        this.id = rootState.getWorkflowId()
        this.startedAt = rootState.getStartedAt()!!
        this.rawInput = rootState.getRawInput()!!
    }

    // Retrieves the workflow by its name and version
    private val workflow by lazy { workflowParser.getWorkflow(name, version) }

    private val nodeInstances: Map<NodePosition, NodeInstance<*>> by lazy { initInstance() }

    internal lateinit var rootInstance: RootInstance

    private suspend fun runTryCatch(run: suspend () -> JsonNode?): JsonNode? {
        var out: JsonNode?
        do {
            try {
                out = run()
                break
            } catch (e: CaughtDoWorkflowException) {
                // at this stage, the doInstance should have a raw input
                position = e.`do`.node.position
            }
        } while (true)

        return out
    }

    suspend fun run() {
        // Get the task at the current position
        val current = nodeInstances[position] ?: error("task not found in position $position")

        var next = when (isStarting()) {
            // enter root and go to `do`
            true -> {
                status = WorkflowStatus.RUNNING
                current.start()
                current.`continue`()
            }
            // complete the current activity execution (rawOutput should be part of the state)
            false -> current.then()
        }

        // find and execute the next activity
        while (true) {
            // get transformed Input for this node
            next = when (next?.shouldStart()) {
                null -> break
                true -> {
                    // if next is an activity, then break
                    if (next.node.isActivity()) break
                    // execute current node
                    next.execute()
                    // continue
                    next.`continue`()
                }

                false -> next.parent?.`continue`()
            }
        }

        when (next) {
            // complete the workflow
            null -> {
                position = NodePosition.root
                rootInstance.complete()
            }
            // execute the activity
            else -> {
                position = next.node.position
                next.execute()
            }
        }
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
        val rootNode = workflowParser.getRootNode(workflow)
        rootInstance = rootNode.createInstance(null) as RootInstance
        // reinit for this execution
        rootInstance.secrets = workflowParser.getSecrets(workflow)
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
        .apply { states[node.position]?.let { setState(it) } }
        // create all children node instances
        .also { nodeInstance ->
            nodeInstance.children = this.children?.map { child -> child.createInstance(nodeInstance) } ?: emptyList()
        }

    private fun error(message: Any): Nothing =
        throw IllegalStateException("Workflow $name (version $version): $message")
}