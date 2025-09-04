// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processor

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.core.RuntimeDescriptor
import com.lemline.core.activities.ActivityRunnerProvider
import com.lemline.core.definitions.Definitions
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
import com.lemline.core.logger.withWorkflowContext
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.nodes.RootTask
import com.lemline.core.nodes.isGoingDown
import com.lemline.core.nodes.isGoingUp
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowState
import com.lemline.core.workflows.WorkflowVersion
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
import java.util.concurrent.CompletableFuture
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.serialization.json.JsonElement

/**
 * Represents an instance of a workflow, including its state, position, secrets, and execution logic.
 *
 * @property workflowState The workflow instance.
 * @property secrets A map of secrets used in the workflow.
 */
@ExperimentalTime
@Suppress("unused")
class Processor(
    val workflowState: WorkflowState,
    secrets: Map<String, JsonElement>,
    var activityRunnerProvider: ActivityRunnerProvider = ActivityRunnerProvider.default,
) {
    /**
     * Companion object for creating new instances of the workflow.
     */
    companion object {
        /**
         * Creates a new instance of the workflow. This is the primary factory method.
         *
         * For Java users, this method is exposed as a static method on `WorkflowInstance`
         * and provides overloads for optional parameters.
         *
         * @param name The name of the workflow.
         * @param version The version of the workflow.
         * @param id The unique identifier of the workflow instance.
         * @param rawInput The raw input data for the workflow as a JSON node.
         * @param secrets A map of secrets to be used in the workflow.
         * @param activityRunnerProvider The provider for custom activity runners.
         * @return A new instance of the WorkflowInstance class.
         */
        @JvmStatic
        @JvmOverloads
        fun createNew(
            name: WorkflowName,
            version: WorkflowVersion,
            id: WorkflowId,
            rawInput: JsonElement,
            secrets: Map<String, JsonElement> = emptyMap(),
            activityRunnerProvider: ActivityRunnerProvider = ActivityRunnerProvider.default,
        ) = Processor(
            workflowState = WorkflowState(
                workflowId = id,
                workflowName = name,
                workflowVersion = version,
                currentStates = NodeStates.newInstance(rawInput),
                currentPosition = NodePosition.root,
            ),
            secrets = secrets,
            activityRunnerProvider = activityRunnerProvider
        )

        internal val scope = CoroutineScope(Dispatchers.IO)
    }

    private val logger = logger()


    /**
     * The root instance of the workflow, representing the entry point for execution.
     */
    val rootInstance: RootInstance

    /**
     * A map of node positions to their corresponding node instances.
     */
    private val nodeInstances = mutableMapOf<NodePosition, NodeInstance<*>>()

    /**
     * The current status of the workflow instance.
     */
    var status = WorkflowStatus.PENDING

    /**
     * The workflow definition.
     */
    val workflow: Workflow

    init {
        workflow = Definitions.getOrNull(workflowState.workflowName, workflowState.workflowVersion)
            ?: error("workflow ${workflowState.workflowName} (version ${workflowState.workflowVersion}) not found")
        // get root state from instance
        val rootState = workflowState.currentStates[NodePosition.root]
            ?: error("no initial state provided for the root node")
        // get startedAt and rawInput from root state
        val errorStr by lazy { "provided in the root node of the initial state" }
        val startedAt = rootState.startedAt
            ?: error("no startedAt $errorStr")
        val rawInput = rootState.rawInput
            ?: error("no raw input $errorStr")
        // create the root instance
        val rootNode = Definitions.getRootNode(workflow)
        rootInstance = (rootNode.createInstance(workflowState.currentStates, null) as RootInstance)
        rootInstance.processor = this
        rootInstance.secrets = secrets
        rootInstance.runtimeDescriptor = RuntimeDescriptor
        rootInstance.workflowDescriptor = WorkflowDescriptor(
            id = workflowState.workflowId.toString(),
            definition = LemlineJson.encodeToElement(workflow),
            input = rawInput,
            startedAt = LemlineJson.encodeToElement(DateTimeDescriptor.from(startedAt.toJavaInstant())),
        )
        // create nodes instance map for easy access
        fun collect(nodeInstance: NodeInstance<*>) {
            nodeInstances[nodeInstance.node.position] = nodeInstance
            nodeInstance.children.forEach { collect(it) }
        }
        collect(rootInstance)
    }


    /**
     * The current node instance in the workflow execution.
     */
    var current: NodeInstance<*>? = nodeInstances[workflowState.currentPosition]
        ?: error("node not found at initial position ${workflowState.currentPosition}")

    /**
     * Retrieves the current position in the workflow.
     */
    val position: NodePosition?
        get() = current?.node?.position

    /**
     * Retrieves the current states of all nodes in the workflow.
     *
     * @return A map of node positions to their corresponding node states.
     */
    val state: NodeStates
        get() {
            val mapOfStates = mutableMapOf<NodePosition, NodeState>()
            fun collectStates(nodeInstance: NodeInstance<*>) {
                if (nodeInstance.state != NodeState()) {
                    mapOfStates[nodeInstance.node.position] = nodeInstance.state
                }
                nodeInstance.children.forEach { collectStates(it) }
            }
            collectStates(rootInstance)
            return NodeStates(mapOfStates)
        }

    /**
     * Executes the workflow asynchronously. This is the recommended method for Java users
     * who need non-blocking execution.
     *
     * @return A `CompletableFuture` that completes with the workflow result, or completes
     * exceptionally if the workflow faults.
     */
    fun runAsync(): CompletableFuture<JsonElement> = scope.future {
        run()
    }

    /**
     * Executes the workflow synchronously, blocking the current thread until completion.
     * This method is provided for convenience for Java users or in testing scenarios.
     *
     * @return The final result of the workflow as a [JsonElement].
     * @throws com.lemline.core.errors.WorkflowException If a non-retryable error occurs during execution.
     */
    fun runBlocking(): JsonElement = kotlinx.coroutines.runBlocking(scope.coroutineContext) {
        run()
    }

    /**
     * Executes the workflow until completion or a non-retryable error. This is a suspending
     * function and is the idiomatic way to run a workflow from Kotlin coroutines.
     *
     * This method implements the main workflow execution loop with error handling:
     * - Sets the workflow status to RUNNING.
     * - Executes the workflow nodes until completion.
     * - If a non-retryable error occurs, it is thrown to the caller.
     * - If a retryable error occurs, it internally handles the delay and continues execution.
     *
     * @return The final result of the workflow as a [JsonElement].
     * @throws [com.lemline.core.errors.WorkflowException] If a non-retryable error occurs during workflow execution.
     */
    suspend fun run(): JsonElement {
        status = WorkflowStatus.RUNNING

        do {
            try {
                tryRun()
            } catch (e: WorkflowException) {
                onTaskFaulted(current!!)

                // get the try instance catching this, if any
                val tryInstance: TryInstance? = e.getTry()

                // the error was not caught
                if (tryInstance == null) {
                    // the workflow is faulted
                    status = WorkflowStatus.FAULTED
                    onWorkflowFaulted()
                    throw e
                }

                logInfo { "Caught workflow exception: ${e.error}" }
                // reset the state of current nodes up to the tryInstance
                current?.resetUpTo(tryInstance)
                // returning to the TryInstance
                current = tryInstance

                // retry if the TryInstance has a delay configured
                if (tryInstance.delay != null) {
                    logInfo { "Retry with delay: ${tryInstance.delay}" }
                    // reinit childIndex, as we are going to retry
                    tryInstance.childIndex = -1
                    // Update node position after setting retry
                    onTaskRetried(current as TryInstance, e)
                    // Suspend execution for the duration of the delay.
                    delay(tryInstance.delay!!)
                    // Continue the loop to re-execute the TryInstance's children.
                    continue
                }

                // if the tryInstance is not retryable, we just continue with the catch node
                val next = tryInstance.catchDoInstance
                    ?: (tryInstance.then().also { tryInstance.rawOutput = tryInstance.transformedInput })

                goTo(next)
            }
        } while (current != null)

        // If the loop completes, the workflow has finished successfully.
        status = WorkflowStatus.COMPLETED
        onWorkflowCompleted()
        logDebug { "Workflow status: $status, current position: $position" }

        // Return the final transformed output of the root node.
        return rootInstance.transformedOutput
    }

    /**
     * Executes the current node in the workflow.
     *
     * This method handles the execution logic for individual nodes, including
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
                node.startedAt == null -> when (node.shouldStart()) {
                    // run the current task
                    true -> {
                        node.startedAt = Clock.System.now()
                        if (node is WaitInstance) status = WorkflowStatus.WAITING
                        if (node == rootInstance) onWorkflowStarted() else onTaskStarted(node)
                        node.run()
                    }

                    // skip the current task
                    false -> skipTo(node.parent?.`continue`()!!)
                }

                // continue after task execution
                else -> {
                    if (node is WaitInstance) status = WorkflowStatus.RUNNING
                    goTo(if (node.rawOutput == null) node.`continue`() else node.then())
                }
            }
        }
    }

    private fun skipTo(next: NodeInstance<*>) {
        when {
            current.isGoingUp(next) -> current?.skippingUpTo(next)
            else -> current?.skippingSideTo(next)
        }
        current = next
    }

    private fun goTo(next: NodeInstance<*>?) {
        when {
            next == null -> {
                current?.reset()
                current = null
                onWorkflowCompleted()
            }

            current.isGoingUp(next) -> {
                val prev = current!!
                current!!.goingUpTo(next)
                current = next
                onTaskCompleted(prev)
            }

            current.isGoingDown(next) -> {
                current!!.goingDownTo(next)
                current = next
            }

            // Going to self or sibling
            else -> {
                val prev = current!!
                current!!.goingSideTo(next)
                current = next
                onTaskCompleted(prev)
            }
        }
    }

    /**
     * Creates a node instance based on the node type and recursively builds the node tree.
     *
     * @param initialState Map of saved states by position to restore workflow state.
     * @param parent The parent node instance (null only for root nodes).
     * @return The created node instance with its complete subtree.
     * @throws IllegalArgumentException If an unknown task type is encountered.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Node<*>.createInstance(
        initialState: NodeStates,
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
        // copy the initial state for this node position
        .apply { initialState[node.position]?.let { state = it.copy() } }
        // create all children node instances
        .also { nodeInstance ->
            nodeInstance.children =
                this.children?.map { child -> child.createInstance(initialState, nodeInstance) } ?: emptyList()
        }

    /**
     * Sets a handler to be invoked when the workflow starts.
     *
     * @param handler A lambda function to execute when the workflow starts.
     */
    fun onWorkflowStarted(handler: () -> Unit) {
        onWorkflowStarted = handler
    }

    /**
     * Sets a handler to be invoked when the workflow completes.
     *
     * @param handler A lambda function to execute when the workflow completes.
     */
    fun onWorkflowCompleted(handler: () -> Unit) {
        onWorkflowCompleted = handler
    }

    /**
     * Sets a handler to be invoked when the workflow encounters a fault.
     *
     * @param handler A lambda function to execute when the workflow faults.
     */
    fun onWorkflowFaulted(handler: () -> Unit) {
        onWorkflowFaulted = handler
    }

    /**
     * Sets a handler to be invoked when a task starts.
     *
     * @param handler A lambda function to execute when a task starts.
     */
    fun onTaskStarted(handler: (t: NodeInstance<*>) -> Unit) {
        onTaskStarted = handler
    }

    /**
     * Sets a handler to be invoked when a task completes.
     *
     * @param handler A lambda function to execute when a task completes.
     */
    fun onTaskCompleted(handler: (t: NodeInstance<*>) -> Unit) {
        onTaskCompleted = handler
    }

    /**
     * Sets a handler to be invoked when a task encounters a fault.
     *
     * @param handler A lambda function to execute when a task faults.
     */
    fun onTaskFaulted(handler: (t: NodeInstance<*>) -> Unit) {
        onTaskFaulted = handler
    }

    /**
     * Sets a handler to be invoked when a task is retried.
     *
     * @param handler A lambda function to execute when a task is retried.
     */
    fun onTaskRetried(handler: (t: TryInstance, e: WorkflowException) -> Unit) {
        onTaskRetried = handler
    }

    // Default event handlers
    private var onWorkflowStarted = { }
    private var onWorkflowCompleted = { }
    private var onWorkflowFaulted = { }
    private var onTaskStarted = { t: NodeInstance<*> -> }
    private var onTaskCompleted = { t: NodeInstance<*> -> }
    private var onTaskFaulted = { t: NodeInstance<*> -> }
    private var onTaskRetried = { t: TryInstance, e: WorkflowException -> }

    /**
     * Logs debug messages with workflow context.
     *
     * @param e Optional throwable to include in the log.
     * @param message Lambda providing the log message.
     */
    private fun logDebug(e: Throwable? = null, message: () -> String) = withWorkflowContext(
        workflowId = workflowState.workflowId,
        workflowName = workflowState.workflowName,
        workflowVersion = workflowState.workflowVersion,
        currentPosition = position,
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
        workflowId = workflowState.workflowId,
        workflowName = workflowState.workflowName,
        workflowVersion = workflowState.workflowVersion,
        currentPosition = position,
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
        workflowId = workflowState.workflowId,
        workflowName = workflowState.workflowName,
        workflowVersion = workflowState.workflowVersion,
        currentPosition = position,
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
            workflowId = workflowState.workflowId,
            workflowName = workflowState.workflowName,
            workflowVersion = workflowState.workflowVersion,
            currentPosition = position,
        ) {
            logger.error(e, message)
        }
    }

    /**
     * Retrieves the final output of the workflow.
     */
    fun getOutput(): JsonElement = when (status) {
        WorkflowStatus.COMPLETED -> rootInstance.transformedOutput
        else -> error("Workflow is not completed yet")
    }

    /**
     * Throws an error with a formatted message.
     *
     * @param message The error message to include.
     * @throws IllegalStateException Always thrown with the provided message.
     */
    private fun error(message: Any): Nothing =
        throw IllegalStateException("Workflow ${workflowState.workflowName} (version ${workflowState.workflowVersion})): $message")
}
