package com.lemline.sw.workflows

import com.lemline.common.info
import com.lemline.common.json.Json
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.sw.errors.WorkflowException
import com.lemline.sw.expressions.scopes.RuntimeDescriptor
import com.lemline.sw.expressions.scopes.WorkflowDescriptor
import com.lemline.sw.nodes.*
import com.lemline.sw.nodes.activities.*
import com.lemline.sw.nodes.flows.*
import com.lemline.worker.messaging.WorkflowMessage
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.WorkflowStatus
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Represents a initialPosition of a workflow.
 *
 * @property name The name of the workflow.
 * @property version The version of the workflow.
 * @property id The unique identifier of the workflow initialPosition.
 * @property rawInput The raw input data for the initialPosition as a JSON node.
 *
 */
class WorkflowInstance(
    val name: String,
    val version: String,
    val initialStates: Map<NodePosition, NodeState>,
    val initialPosition: NodePosition
) {
    internal lateinit var workflowParser: WorkflowParser

    private val logger = logger()

    /**
     * Instance status
     */
    internal var status = WorkflowStatus.PENDING

    /**
     * Instance ID
     * (parsed from the initialStates)
     */
    internal val id: String

    /**
     * Instance starting date
     * (parsed from the initialStates)
     */
    internal val startedAt: Instant

    /**
     * Instance raw input
     * (parsed from the initialStates)
     */
    internal val rawInput: JsonElement

    init {
        val rootState = initialStates[NodePosition.root] ?: error("no initialStates provided for the root node")

        this.id = rootState.workflowId!!
        this.startedAt = rootState.startedAt!!
        this.rawInput = rootState.rawInput!!
    }

    // Retrieves the workflow by its name and version
    private val workflow by lazy { workflowParser.getWorkflow(name, version) }

    private val nodeInstances: Map<NodePosition, NodeInstance<*>> by lazy { initInstance() }

    internal lateinit var rootInstance: RootInstance

    /**
     * The current node instance in the workflow.
     *
     * This property is initialized when the workflow is run and represents
     * the current nodeInstance in the workflow execution.
     */
    private var _currentNodeInstance: NodeInstance<*>? = null
    internal var currentNodeInstance: NodeInstance<*>
        get() = _currentNodeInstance
            ?: nodeInstances[initialPosition].also { _currentNodeInstance = it }
            ?: error("task not found in initialPosition $initialPosition")
        set(value) {
            _currentNodeInstance = value
        }

    /**
     * Retrieves the current position in the workflow.
     */
    internal val currentNodePosition: NodePosition
        get() = currentNodeInstance.node.position

    /**
     * Retrieves the current initialStates of all nodes in the workflow.
     *
     * This property collects the initialStates of all nodes starting from the root instance
     * and returns them as a map where the keys are the node positions and the values
     * are the node initialStates.
     *
     * @return A map of node positions to their corresponding node initialStates.
     */
    internal val currentNodeStates: Map<NodePosition, NodeState>
        get() {
            val currentStates = mutableMapOf<NodePosition, NodeState>()
            fun collectStates(nodeInstance: NodeInstance<*>) {
                if (nodeInstance.state != NodeState()) {
                    currentStates[nodeInstance.node.position] = nodeInstance.state
                }
                nodeInstance.children.forEach { collectStates(it) }
            }
            collectStates(rootInstance)
            return currentStates
        }

    suspend fun run() {
        var current = currentNodeInstance
        status = WorkflowStatus.RUNNING

        do {
            try {
                current.run()
                break
            } catch (e: WorkflowException) {
                val tryInstance = e.catching
                // the error was not caught
                if (tryInstance == null) {
                    logger.warn { "Workflow $id $name($version): ${e.error}" }
                    // the workflow is faulted
                    status = WorkflowStatus.FAULTED
                    // and stopped there
                    current = e.raising
                    break
                }
                logger.info { "Workflow $id $name($version): ${e.error}" }
                if (tryInstance.delay != null) {
                    // reinit childIndex, as we are going to retry
                    tryInstance.childIndex = -1
                    // current being a TryInstance, implies that it should be retried
                    current = tryInstance
                    break
                }
                // continue with the catch node if any, or just continue if none
                current = tryInstance.catchDoInstance?.also { it.rawInput = tryInstance.transformedInput }
                    ?: tryInstance.then() ?: rootInstance
            }
        } while (true)

        currentNodeInstance = current
    }

    private suspend fun NodeInstance<*>.run() {

        // Possible cases when starting here:
        // - starting a workflow
        // - continuing right after an activity execution (before transformedOutput is set)
        // - restarting a TryInstance

        var next = when (rawOutput == null || this is TryInstance) {
            // current node position is not completed (start or retry)
            true -> this
            // current node position is completed, go to next
            false -> then()
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
                    // execute current NodeInstance flow node
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
                currentNodeInstance = rootInstance
                //rootInstance.complete()
                status = WorkflowStatus.COMPLETED
            }
            // execute the activity
            else -> {
                currentNodeInstance = next
                next.execute()
                if (next is WaitInstance) status = WorkflowStatus.WAITING
            }
        }
    }

    /**
     * Retrieves the task instances of the given workflow with the provided initialStates.
     *
     * This method initializes the node instance with the provided initialStates,
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
            definition = Json.encodeToElement(workflow),
            input = rawInput,
            startedAt = Json.encodeToElement(DateTimeDescriptor.from(startedAt))
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
        // apply initialStates for this new node position
        .apply { initialStates[node.position]?.let { state = it } }
        // create all children node instances
        .also { nodeInstance ->
            nodeInstance.children = this.children?.map { child -> child.createInstance(nodeInstance) } ?: emptyList()
        }

    private fun error(message: Any): Nothing =
        throw IllegalStateException("Workflow $name (version $version): $message")


    /**
     * Converts the current workflow instance to a `WorkflowMessage`.
     *
     * @return A `WorkflowMessage` representing the state of the workflow instance.
     */
    internal fun toMessage() = WorkflowMessage(
        name = name,
        version = version,
        states = currentNodeStates,
        position = currentNodePosition
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
            initialStates = msg.states,
            initialPosition = msg.position
        )
    }
}