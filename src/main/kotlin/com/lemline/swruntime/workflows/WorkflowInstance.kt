package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.expressions.scopes.RuntimeDescriptor
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor
import com.lemline.swruntime.tasks.NodePosition
import com.lemline.swruntime.tasks.NodeState
import com.lemline.swruntime.tasks.instances.NodeInstance
import com.lemline.swruntime.tasks.instances.RootInstance
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
     * Instance Id
     * (parsed from the state)
     */
    private val id: String

    /**
     * Instance starting date
     * (parsed from the state)
     */
    private val startedAt: Instant

    /**
     * Instance raw input
     * (parsed from the state)
     */
    private val rawInput: JsonNode

    init {
        val rootState = state[NodePosition.root]
        require(rootState != null) {
            error("no state provided for the root node")
        }

        val workflowDesc = rootState[RootInstance.WORKFLOW_STATE_KEY]
        require(workflowDesc != null) {
            error("no workflow description provided")
        }

        val id = workflowDesc["id"]
        require(id != null && id.asText().isNotEmpty()) {
            error("invalid id value provided: $id")
        }
        this.id = id.asText()

        val startedAt = workflowDesc["startedAt"]
        require(startedAt != null && startedAt.asText().isValidInstant()) {
            error("invalid startedAt value provided: $startedAt")
        }
        this.startedAt = Instant.parse(startedAt.asText())

        val rawInput = workflowDesc["input"]
        require(rawInput != null) {
            error("no workflow input provided")
        }
        this.rawInput = rawInput
    }

    // Retrieves the workflow by its name and version
    private val workflow by lazy { workflowService.getWorkflow(name, version) }

    private val nodeInstances: Map<NodePosition, NodeInstance<*>> by lazy { initInstance() }

    private lateinit var rootInstance: RootInstance

    suspend fun run() {
        // Get the task at the current position
        val activity = nodeInstances[position] ?: error("task not found in position $position")

        // Complete the current task execution (rawOutput should be part of the state)
        if (!isStarting()) activity.complete()

        // find and execute the next activity
        activity.nextActivity()?.also {
            it.rawOutput = it.execute()
        }
    }

    private fun isStarting(): Boolean = position == NodePosition.root && rootInstance.childIndex == null

    fun isCompleted(): Boolean = position == NodePosition.root && rootInstance.childIndex == 1

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
        rootInstance = RootInstance.from(rootNode, state)
        // reinit
        rootInstance.secrets = workflowService.getSecrets(workflow)
        rootInstance.runtimeDescriptor = RuntimeDescriptor
        rootInstance.workflowDescriptor = WorkflowDescriptor(
            id = id,
            definition = workflow,
            input = rawInput,
            startedAt = DateTimeDescriptor.from(startedAt)
        )

        val nodeInstances = mutableMapOf<NodePosition, NodeInstance<*>>()

        fun collect(nodeInstance: NodeInstance<*>) {
            nodeInstances[nodeInstance.node.position] = nodeInstance
            nodeInstance.children.forEach { collect(it) }
        }

        collect(rootInstance)

        return nodeInstances
    }

    internal fun getState(): Map<NodePosition, NodeState> {
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
        return nextActivity
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