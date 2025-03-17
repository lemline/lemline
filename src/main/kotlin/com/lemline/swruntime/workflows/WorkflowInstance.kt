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
import java.time.Instant
import java.time.format.DateTimeParseException

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
    private lateinit var workflowService: WorkflowService

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

    private val nodeInstances: Map<NodePosition, NodeInstance<*>> by lazy { initInstance() }

    private lateinit var rootInstance: RootInstance

    suspend fun run() {
        // Get the task at the current position
        val activity = nodeInstances[position] ?: error("task not found in position $position")

        // Complete the current activity execution (rawOutput should be part of the state)
        if (!isStarting()) activity.complete()

        // find and execute the next activity
        activity.nextActivity()?.also {
            it.rawOutput = it.execute()
        }
    }

    private fun isStarting(): Boolean = position == NodePosition.root && rootInstance.childIndex == null

    internal fun isCompleted(): Boolean = position == NodePosition.root && rootInstance.childIndex == 1

    /**
     * Retrieves the task instances of the given workflow with the provided state.
     *
     * This method initializes the root instance with the provided state,
     * and then recursively populates the instance node and its children with the state.
     *
     * @param workflow The workflow definition containing the task instances.
     * @param states The state of the task instances to populate.
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

    private fun NodeInstance<*>.nextActivity(): NodeInstance<*>? {
        var nextActivity: NodeInstance<*>? = next()
        while (true) {
            // if null, then the workflow is completed
            if (nextActivity == null) break
            // if taskInstance should not run, then continue
            if (nextActivity.shouldRun(transformedOutput)) {
                // if next is an activity, then break
                if (nextActivity.node.isActivity()) break
            }
            nextActivity = nextActivity.next()
        }
        return nextActivity?.also { position = it.node.position }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Node<*>.createInstance(parent: NodeInstance<*>?): NodeInstance<*> = when (task) {
        is RootTask -> RootInstance(this as Node<RootTask>)
        is DoTask -> DoInstance(this as Node<DoTask>, parent!!)
        is ForTask -> ForInstance(this as Node<ForTask>, parent!!)
        is TryTask -> TryInstance(this as Node<TryTask>, parent!!)
        is ForkTask -> ForkInstance(this as Node<ForkTask>, parent!!)
        is RaiseTask -> RaiseInstance(this as Node<RaiseTask>, parent!!)
        is SetTask -> SetInstance(this as Node<SetTask>, parent!!)
        is SwitchTask -> SwitchInstance(this as Node<SwitchTask>, parent!!)
        is CallAsyncAPI -> CallAsyncApiInstance(this as Node<CallAsyncAPI>, parent!!)
        is CallGRPC -> CallGrpcInstance(this as Node<CallGRPC>, parent!!)
        is CallHTTP -> CallHttpInstance(this as Node<CallHTTP>, parent!!)
        is CallOpenAPI -> CallOpenApiInstance(this as Node<CallOpenAPI>, parent!!)
        is EmitTask -> EmitInstance(this as Node<EmitTask>, parent!!)
        is ListenTask -> ListenInstance(this as Node<ListenTask>, parent!!)
        is RunTask -> RunInstance(this as Node<RunTask>, parent!!)
        is WaitTask -> WaitInstance(this as Node<WaitTask>, parent!!)
        else -> throw IllegalArgumentException("Unknown task type: ${task.javaClass.name}")
    }
        // apply state for this new node instance
        .apply { state[node.position]?.let { setState(it) } }
        // create all children node instances
        .also { nodeInstance ->
            nodeInstance.children = this.children?.map { child -> child.createInstance(nodeInstance) } ?: emptyList()
        }

    private fun String.isValidInstant(): Boolean = try {
        Instant.parse(this)
        true
    } catch (e: DateTimeParseException) {
        false
    }

    private fun error(message: Any): Nothing =
        throw IllegalStateException("Workflow $name (version $version): $message")

}