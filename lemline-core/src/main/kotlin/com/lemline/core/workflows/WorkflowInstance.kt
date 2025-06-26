// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.common.withWorkflowContext
import com.lemline.core.RuntimeDescriptor
import com.lemline.core.errors.WaitException
import com.lemline.core.errors.WorkflowException
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import com.lemline.core.instances.CallAsyncApiInstance
import com.lemline.core.instances.CallGrpcInstance
import com.lemline.core.instances.CallHttpInstance
import com.lemline.core.instances.CallOpenApiInstance
import com.lemline.core.instances.DoInstance
import com.lemline.core.instances.EmitInstance
import com.lemline.core.instances.ForInstance
import com.lemline.core.instances.ForkInstance
import com.lemline.core.instances.ListenInstance
import com.lemline.core.instances.RaiseInstance
import com.lemline.core.instances.RootInstance
import com.lemline.core.instances.RunInstance
import com.lemline.core.instances.SetInstance
import com.lemline.core.instances.SwitchInstance
import com.lemline.core.instances.TryInstance
import com.lemline.core.instances.WaitInstance
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.nodes.RootTask
import com.lemline.core.nodes.isGoingDown
import com.lemline.core.nodes.isGoingUp
import io.serverlessworkflow.api.types.CallAsyncAPI
import io.serverlessworkflow.api.types.CallGRPC
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.CallOpenAPI
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.EmitTask
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.RaiseTask
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SwitchTask
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.WaitTask
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.WorkflowStatus
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.JsonElement

/**
 * Represents an instance of a workflow, including its state, position, secrets, and execution logic.
 *
 * @property name The name of the workflow.
 * @property version The version of the workflow.
 * @property states A map of node positions to their corresponding node states.
 * @property position The current position in the workflow.
 * @property secrets A map of secrets used in the workflow.
 */
class WorkflowInstance(
    val name: String,
    val version: String,
    states: Map<NodePosition, NodeState>,
    position: NodePosition,
    secrets: Map<String, JsonElement>,
) {

    /**
     * Companion object for creating new instances of the workflow.
     */
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
            secrets: Map<String, JsonElement> = emptyMap(),
        ) = WorkflowInstance(
            name = name,
            version = version,
            states = mapOf(
                NodePosition.root to NodeState(
                    workflowId = id,
                    rawInput = rawInput,
                    startedAt = Clock.System.now(),
                ),
            ),
            position = NodePosition.root,
            secrets = secrets,
        )
    }

    private val logger = logger()

    // Event handlers for workflow and task lifecycle events
    var onWorkflowStarted = {}
    var onWorkflowCompleted = {}
    var onWorkflowFaulted = {}
    var onTaskCreated = {}
    var onTaskStarted = {}
    var onTaskCompleted = {}
    var onTaskFaulted = {}
    var onTaskRetried = {}

    /**
     * Logs debug messages with workflow context.
     *
     * @param e Optional throwable to include in the log.
     * @param message Lambda providing the log message.
     */
    private fun logDebug(e: Throwable? = null, message: () -> String) = withWorkflowContext(
        workflowId = id,
        workflowName = name,
        workflowVersion = version,
        nodePosition = currentPosition.toString(),
    ) {
        logger.debug(e, message)
    }

    /**
     * Logs informational messages with workflow context.
     *
     * @param e Optional throwable to include in the log.
     * @param message Lambda providing the log message.
     */
    private fun logInfo(e: Throwable? = null, message: () -> String) = withWorkflowContext(
        workflowId = id,
        workflowName = name,
        workflowVersion = version,
        nodePosition = currentPosition.toString(),
    ) {
        logger.info(e, message)
    }

    /**
     * Logs warning messages with workflow context.
     *
     * @param e Optional throwable to include in the log.
     * @param message Lambda providing the log message.
     */
    private fun logWarn(e: Throwable? = null, message: () -> String) = withWorkflowContext(
        workflowId = id,
        workflowName = name,
        workflowVersion = version,
        nodePosition = currentPosition.toString(),
    ) {
        logger.warn(e, message)
    }

    /**
     * Logs error messages with workflow context.
     *
     * @param e Optional throwable to include in the log.
     * @param message Lambda providing the log message.
     */
    private fun logError(e: Throwable? = null, message: () -> String) {
        withWorkflowContext(
            workflowId = id,
            workflowName = name,
            workflowVersion = version,
            nodePosition = currentPosition.toString(),
        ) {
            logger.error(e, message)
        }
    }

    /**
     * The workflow definition associated with this instance.
     */
    private val workflow: Workflow

    /**
     * The root instance of the workflow, representing the entry point for execution.
     */
    internal val rootInstance: RootInstance

    /**
     * A map of node positions to their corresponding node instances.
     */
    private val nodeInstances: Map<NodePosition, NodeInstance<*>>

    /**
     * The current status of the workflow instance.
     */
    var status = WorkflowStatus.PENDING

    /**
     * The unique identifier of the workflow instance.
     */
    internal val id: String

    /**
     * The starting date and time of the workflow instance.
     */
    internal val startedAt: Instant

    /**
     * The raw input data for the workflow instance.
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
            startedAt = LemlineJson.encodeToElement(DateTimeDescriptor.from(startedAt.toJavaInstant())),
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
     * The current node instance in the workflow execution.
     */
    var current: NodeInstance<*>? = nodeInstances[position]
        ?: error("node not found in initialPosition $position")

    /**
     * Retrieves the current position in the workflow.
     */
    val currentPosition: NodePosition?
        get() = current?.node?.position

    /**
     * Retrieves the current states of all nodes in the workflow.
     *
     * @return A map of node positions to their corresponding node states.
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

    /**
     * Executes the workflow until completion, error, or waiting state.
     *
     * This method implements the main workflow execution loop with error handling:
     * - Sets the workflow status to RUNNING
     * - Executes the workflow nodes using tryRun() until completion or error
     * - Handles exceptions using TryInstance error handlers if available
     * - Manages retry logic with delays when configured
     * - Updates workflow status based on execution result (COMPLETED, FAULTED, WAITING)
     *
     * @throws WaitException If the workflow enters a waiting state.
     * @throws WorkflowException If an error occurs during workflow execution.
     */
    suspend fun run() {
        status = WorkflowStatus.RUNNING

        do {
            try {
                tryRun()
            } catch (_: WaitException) {
                status = WorkflowStatus.WAITING
                break
            } catch (e: WorkflowException) {
                onTaskFaulted()

                // the error was not caught
                val tryInstance = e.catching
                if (tryInstance == null) {
                    // the workflow is faulted
                    status = WorkflowStatus.FAULTED
                    // and stopped there
                    current = e.raising
                    logError(e) { "Workflow execution faulted" }
                    onWorkflowFaulted()
                    break
                }

                logInfo { "Caught workflow exception: ${e.error}" }

                if (tryInstance.delay?.isPositive() == true) {
                    // reinit childIndex, as we are going to retry
                    tryInstance.childIndex = -1
                    // current being a TryInstance, implies that it should be retried
                    current = tryInstance
                    onTaskRetried()
                    // Update node position after setting retry
                    logInfo { "Scheduling retry with delay: ${tryInstance.delay}" }
                    break
                }

                // continue with the catch node if any, or just continue if none
                current = tryInstance.catchDoInstance?.also {
                    it.rawInput = tryInstance.transformedInput
                    logDebug { "Continuing with catch handler: ${it.node.position}" }
                } ?: tryInstance.then().also {
                    logDebug { "No catch handler, continuing with next node: ${it?.node?.position}" }
                }
            }
        } while (current != null)

        if (current == null) {
            status = WorkflowStatus.COMPLETED
            onWorkflowCompleted()
        }

        logDebug { "Workflow status: $status, current position: $currentPosition" }
    }

    /**
     * Executes the current node in the workflow.
     *
     * This method handles the execution logic for individual nodes, including:
     * - Starting tasks
     * - Skipping tasks
     * - Transitioning to the next node
     *
     * @throws WorkflowException If an error occurs during node execution.
     */
    private suspend fun tryRun() {
        while (current != null) {
            val node = current!!
            when {
                // starting current task
                node.startedAt == null && node.shouldStart() -> {
                    node.startedAt = Clock.System.now()
                    if (node == rootInstance) onWorkflowStarted() else onTaskStarted()
                    node.run()
                }

                // skipped tasks
                node.startedAt == null -> skipTo(node.parent?.`continue`()!!)

                // continue after task execution
                else -> goTo(if (node.rawOutput == null) node.`continue`() else node.then())
            }
        }
    }

    private fun skipTo(next: NodeInstance<*>) {
        when {
            current.isGoingUp(next) -> {
                current?.skippingUpTo(next)
                current = next
            }

            else -> {
                // Going to self or sibling
                current?.skippingSideTo(next)
                current = next
                onTaskCreated()
            }
        }
    }

    private fun goTo(next: NodeInstance<*>?) {
        when {
            next == null -> {
                current?.reset()
                current = null
            }

            current.isGoingUp(next) -> {
                // if the next node is going up, we are done with the current node
                if (current == rootInstance) onWorkflowCompleted() else onTaskCompleted()
                current?.goingUpTo(next)
                current = next
            }

            current.isGoingDown(next) -> {
                // if the next node is going down, we need to set the input for the next node
                current?.goingDownTo(next)
                current = next
                onTaskCreated()
            }

            else -> {
                // Going to self or sibling
                onTaskCompleted()
                current?.goingSideTo(next)
                current = next
                onTaskCreated()
            }
        }
    }

    /**
     * Creates a node instance based on the node type and recursively builds the node tree.
     *
     * @param initialStates Map of saved states by position to restore workflow state.
     * @param parent The parent node instance (null only for root nodes).
     * @return The created node instance with its complete subtree.
     * @throws IllegalArgumentException If an unknown task type is encountered.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Node<*>.createInstance(
        initialStates: Map<NodePosition, NodeState>,
        parent: NodeInstance<*>?,
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

    /**
     * Throws an error with a formatted message.
     *
     * @param message The error message to include.
     * @throws IllegalStateException Always thrown with the provided message.
     */
    private fun error(message: Any): Nothing =
        throw IllegalStateException("Workflow $name (version $version): $message")
}
