package com.lemline.swruntime.sw.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.logger
import com.lemline.swruntime.messaging.WorkflowMessage
import com.lemline.swruntime.sw.errors.CaughtDoWorkflowException
import com.lemline.swruntime.sw.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.sw.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.sw.tasks.*
import com.lemline.swruntime.sw.tasks.activities.*
import com.lemline.swruntime.sw.tasks.flows.*
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
    val states: Map<NodePosition, NodeState>,
    val position: NodePosition
) {
    @Inject
    internal lateinit var workflowParser: WorkflowParser

    private val logger = logger()

    /**
     * Instance status
     */
    internal var status = WorkflowStatus.PENDING

    /**
     * Instance WORKFLOW_ID
     * (parsed from the states)
     */
    private val id: String

    /**
     * Instance starting date
     * (parsed from the states)
     */
    private val startedAt: DateTimeDescriptor

    /**
     * Instance raw input
     * (parsed from the states)
     */
    private val rawInput: JsonNode

    init {
        val rootState = states[NodePosition.root] ?: error("no states provided for the root node")

        this.id = rootState.workflowId!!
        this.startedAt = rootState.startedAt!!
        this.rawInput = rootState.rawInput!!
    }

    // Retrieves the workflow by its name and version
    private val workflow by lazy { workflowParser.getWorkflow(name, version) }

    private val nodeInstances: Map<NodePosition, NodeInstance<*>> by lazy { initInstance() }

    internal lateinit var rootInstance: RootInstance

    internal lateinit var current: NodeInstance<*>

    suspend fun run() {
        var current = nodeInstances[position] ?: error("task not found in position $position")
        status = WorkflowStatus.RUNNING

        do {
            try {
                current.run()
                break
            } catch (e: CaughtDoWorkflowException) {
                // go to `do` task
                current = e.`do`
            }
        } while (true)
    }

    private suspend fun NodeInstance<*>.run() {

        var next = when (rawOutput == null) {
            // current is not completed (start or retry)
            true -> this
            // complete, and go to next
            false -> this.then()
        }

        // find and execute the next activity
        while (true) {
            // get transformed Input for this node
            next = when (next?.shouldStart()) {
                // reached workflow's end
                null -> break
                true -> {
                    // if next is an activity, then break
                    if (next.node.isActivity()) break
                    // execute current flow node
                    next.execute()
                    // continue flow node
                    next.`continue`()
                }

                false -> next.parent?.`continue`()
            }
        }

        when (next) {
            // complete the workflow
            null -> {
                this@WorkflowInstance.current = rootInstance
                rootInstance.complete()
                status = WorkflowStatus.COMPLETED
            }
            // execute the activity
            else -> {
                this@WorkflowInstance.current = next
                next.execute()
                if (next is WaitInstance) status = WorkflowStatus.WAITING
            }
        }
    }

    /**
     * Retrieves the task instances of the given workflow with the provided states.
     *
     * This method initializes the root instance with the provided states,
     * and then recursively populates the instance node and its children with the states.
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

    @Suppress("UNCHECKED_CAST")
    private fun NodeTask<*>.createInstance(parent: NodeInstance<*>?): NodeInstance<*> = when (task) {
        is RootTask -> RootInstance(this as NodeTask<RootTask>)
        is DoTask -> DoInstance(this as NodeTask<DoTask>, parent!!)
        is ForTask -> ForInstance(this as NodeTask<ForTask>, parent!!)
        is TryTask -> TryInstance(this as NodeTask<TryTask>, parent!!)
        is ForkTask -> ForkInstance(this as NodeTask<ForkTask>, parent!!)
        is RaiseTask -> com.lemline.swruntime.sw.tasks.flows.RaiseInstance(this as NodeTask<RaiseTask>, parent!!)
        is SetTask -> SetInstance(this as NodeTask<SetTask>, parent!!)
        is SwitchTask -> com.lemline.swruntime.sw.tasks.flows.SwitchInstance(this as NodeTask<SwitchTask>, parent!!)
        is CallAsyncAPI -> CallAsyncApiInstance(this as NodeTask<CallAsyncAPI>, parent!!)
        is CallGRPC -> CallGrpcInstance(this as NodeTask<CallGRPC>, parent!!)
        is CallHTTP -> CallHttpInstance(this as NodeTask<CallHTTP>, parent!!)
        is CallOpenAPI -> CallOpenApiInstance(this as NodeTask<CallOpenAPI>, parent!!)
        is EmitTask -> EmitInstance(this as NodeTask<EmitTask>, parent!!)
        is ListenTask -> ListenInstance(this as NodeTask<ListenTask>, parent!!)
        is RunTask -> com.lemline.swruntime.sw.tasks.activities.RunInstance(this as NodeTask<RunTask>, parent!!)
        is WaitTask -> com.lemline.swruntime.sw.tasks.activities.WaitInstance(this as NodeTask<WaitTask>, parent!!)
        else -> throw IllegalArgumentException("Unknown task type: ${task.javaClass.name}")
    }
        // apply states for this new node instance
        .apply { states[node.position]?.let { state = it } }
        // create all children node instances
        .also { nodeInstance ->
            nodeInstance.children = this.children?.map { child -> child.createInstance(nodeInstance) } ?: emptyList()
        }

    private fun error(message: Any): Nothing =
        throw IllegalStateException("Workflow $name (version $version): $message")


    /**
     * Converts the current workflow instance to a `WorkflowExecutionMessage`.
     *
     * @return A `WorkflowExecutionMessage` representing the current state of the workflow instance.
     */
    internal fun toMessage() = WorkflowMessage(
        name = name,
        version = version,
        states = states
            .mapKeys { it.key.jsonPointer }
            .mapValues { it.value.toJson() }
            .filterValues { it != null }
            .mapValues { it.value!! },
        position = position.jsonPointer
    )

    companion object {

        /**
         * Creates a `WorkflowInstance` from a `WorkflowExecutionMessage`.
         *
         * @param msg The `WorkflowExecutionMessage` containing the workflow execution details.
         * @return A new `WorkflowInstance` initialized with the data from the message.
         */
        internal fun from(msg: WorkflowMessage) = WorkflowInstance(
            name = msg.name,
            version = msg.version,
            states = msg.states
                .mapKeys { it.key.toPosition() }
                .mapValues { NodeState.fromJson(it.value) },
            position = msg.position.toPosition()
        )
    }
}