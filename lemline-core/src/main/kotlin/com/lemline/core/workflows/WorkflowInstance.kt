package com.lemline.core.workflows

import com.lemline.common.*
import com.lemline.core.RuntimeDescriptor
import com.lemline.core.errors.WorkflowException
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.*
import com.lemline.core.nodes.activities.*
import com.lemline.core.nodes.flows.*
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.WorkflowStatus
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.JsonElement

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
    states: Map<NodePosition, NodeState>,
    position: NodePosition,
    secrets: Map<String, JsonElement>,
) {

    companion object {
        /**
         * Creates a new instance of the workflow.
         *
         * @param name The name of the workflow.
         * @param version The version of the workflow.
         * @param id The unique identifier of the workflow instance.
         * @param rawInput The raw input data for the workflow as a JSON node.
         * @param secrets A map of secrets to be used in the workflow.
         * @return A new instance of the WorkflowInstance class.
         */
        fun createNew(
            name: String,
            version: String,
            id: String,
            rawInput: JsonElement,
            secrets: Map<String, JsonElement> = emptyMap()
        ) = WorkflowInstance(
            name = name,
            version = version,
            states = mapOf(
                NodePosition.root to NodeState(
                    workflowId = id,
                    rawInput = rawInput,
                    startedAt = Clock.System.now(),
                )
            ),
            position = NodePosition.root,
            secrets = secrets
        )
    }

    private val logger = logger()

    /**
     * Local debug function that sets the workflow context each time it's called
     */
    private fun debug(e: Throwable? = null, message: () -> String) = withWorkflowContext(
        workflowId = id,
        workflowName = name,
        workflowVersion = version,
        nodePosition = currentPosition.toString()
    ) {
        logger.debug(e, message)
    }

    /**
     * Local info function that sets the workflow context each time it's called
     */
    private fun info(e: Throwable? = null, message: () -> String) = withWorkflowContext(
        workflowId = id,
        workflowName = name,
        workflowVersion = version,
        nodePosition = currentPosition.toString()
    ) {
        logger.info(e, message)
    }

    /**
     * Local error function that sets the workflow context each time it's called
     */
    private fun error(e: Throwable? = null, message: () -> String) {
        withWorkflowContext(
            workflowId = id,
            workflowName = name,
            workflowVersion = version,
            nodePosition = currentPosition.toString()
        ) {
            logger.error(e, message)
        }
    }

    /**
     * Workflow definition
     */
    private val workflow: Workflow

    /**
     * The root instance of the workflow.
     *
     * This property represents the root node instance of the workflow, which is initialized
     * during the creation of the `WorkflowInstance`. It serves as the entry point for the
     * workflow execution and contains the root-level task and the workflow scope.
     */
    internal val rootInstance: RootInstance

    /**
     * Map of node positions to their corresponding node instances.
     */
    private val nodeInstances: Map<NodePosition, NodeInstance<*>>

    /**
     * Instance status
     */
    var status = WorkflowStatus.PENDING

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
        workflow = Workflows.getOrNull(name, version) ?: error("workflow $name (version $version) not found")

        // init workflow data
        val rootState = states[NodePosition.root] ?: error("no initial state provided for the root node")
        val errorStr by lazy { "provided in the root node of the initial state" }
        this.id = rootState.workflowId ?: error("no workflow id $errorStr")
        this.startedAt = rootState.startedAt ?: error("no startedAt $errorStr")
        this.rawInput = rootState.rawInput ?: error("no raw input $errorStr")

        // init root instance and workflow scope
        val rootNode = Workflows.getRootNode(workflow)
        rootInstance = rootNode.createInstance(states, null) as RootInstance
        rootInstance.secrets = secrets
        rootInstance.runtimeDescriptor = RuntimeDescriptor
        rootInstance.workflowDescriptor = WorkflowDescriptor(
            id = id,
            definition = LemlineJson.encodeToElement(workflow),
            input = rawInput,
            startedAt = LemlineJson.encodeToElement(DateTimeDescriptor.from(startedAt.toJavaInstant()))
        )

        // init nodes instance
        val nodeInstances = mutableMapOf<NodePosition, NodeInstance<*>>()
        fun collect(nodeInstance: NodeInstance<*>) {
            nodeInstances[nodeInstance.node.position] = nodeInstance
            nodeInstance.children.forEach { collect(it) }
        }
        collect(rootInstance)

        this.nodeInstances = nodeInstances
    }

    /**
     * The current node instance in the workflow.
     *
     * This property is initialized when the workflow is run and represents
     * the current nodeInstance in the workflow execution.
     */
    var current: NodeInstance<*>? = nodeInstances[position]
        ?: error("node not found in initialPosition $position")

    /**
     * Retrieves the current position in the workflow.
     */
    val currentPosition: NodePosition?
        get() = current?.node?.position

    /**
     * Retrieves the current initialStates of all nodes in the workflow.
     *
     * This property collects the initialStates of all nodes starting from the root instance
     * and returns them as a map where the keys are the node positions and the values
     * are the node initialStates.
     *
     * @return A map of node positions to their corresponding node initialStates.
     */
    val currentNodeStates: Map<NodePosition, NodeState>
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
        status = WorkflowStatus.RUNNING

        debug { "Starting workflow execution" }

        do {
            try {
                tryRun()
                debug { "Workflow execution ran successfully" }
                break
            } catch (e: WorkflowException) {
                val tryInstance = e.catching
                // the error was not caught
                if (tryInstance == null) {
                    // the workflow is faulted
                    status = WorkflowStatus.FAULTED
                    // and stopped there
                    current = e.raising
                    error(e) { "Workflow execution faulted" }
                    break
                }

                info { "Caught workflow exception: ${e.error}" }

                if (tryInstance.delay != null) {
                    // reinit childIndex, as we are going to retry
                    tryInstance.childIndex = -1
                    // current being a TryInstance, implies that it should be retried
                    current = tryInstance
                    // Update node position after setting retry
                    info { "Scheduling retry with delay: ${tryInstance.delay}" }
                    break
                }

                // continue with the catch node if any, or just continue if none
                current = tryInstance.catchDoInstance?.also {
                    it.rawInput = tryInstance.transformedInput
                    debug { "Continuing with catch handler: ${it.node.position}" }
                } ?: tryInstance.then().also {
                    debug { "No catch handler, continuing with next node: ${it?.node?.position}" }
                }
            }
        } while (current != null)

        debug { "Workflow status: $status, current position: $currentPosition" }
    }

    private suspend fun tryRun() {

        // Possible cases when starting here:
        // - starting a workflow
        // - continuing right after an activity execution (before transformedOutput is set)
        // - restarting a TryInstance

        current = when (current!!.rawOutput == null || current is TryInstance) {
            // the current node position is not completed (start or retry)
            true -> current
            // the current node position is completed, go to next
            false -> current?.then()
        }

        // find and execute the next activity
        while (true) {
            val current = current ?: break
            // get transformed Input for this node
            this.current = when (current.shouldStart()) {
                true -> {
                    // if next is an activity, then break
                    if (current.node.isActivity()) break
                    // execute current NodeInstance flow node
                    current.execute()
                    // continue flow node
                    current.`continue`()
                }

                false -> current.parent?.`continue`()
            }
        }

        when (current) {
            null -> status = WorkflowStatus.COMPLETED

            // execute the activity
            else -> {
                current?.execute()
                if (current is WaitInstance) status = WorkflowStatus.WAITING
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Node<*>.createInstance(
        initialStates: Map<NodePosition, NodeState>,
        parent: NodeInstance<*>?
    ): NodeInstance<*> = when (task) {
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
            nodeInstance.children =
                this.children?.map { child -> child.createInstance(initialStates, nodeInstance) } ?: emptyList()
        }

    private fun error(message: Any): Nothing =
        throw IllegalStateException("Workflow $name (version $version): $message")

}
