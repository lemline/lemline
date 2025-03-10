package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.repositories.WorkflowDefinitionRepository
import com.lemline.swruntime.system.System
import com.lemline.swruntime.tasks.TaskPosition
import com.lemline.swruntime.tasks.TaskToken.*
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.json.JsonUtils
import jakarta.enterprise.context.ApplicationScoped
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

typealias WorkflowIndex = Pair<String, String>

@ApplicationScoped
class WorkflowService(
    private val workflowDefinitionRepository: WorkflowDefinitionRepository,
) {
    /**
     * Retrieves a workflow definition by its name and version.
     *
     * This method first checks the cache for the workflow definition. If not found,
     * it loads the workflow definition from the database, validates it, caches it, and returns it.
     *
     * @param name The name of the workflow.
     * @param version The version of the workflow.
     * @return The workflow definition.
     * @throws IllegalArgumentException if the workflow definition is not found in the database.
     */
    fun getWorkflow(name: String, version: String): Workflow =
        workflowCache.getOrPut(name to version) {
            // Load workflow definition from database
            val workflowDefinition = workflowDefinitionRepository.findByNameAndVersion(name, version)
                ?: throw IllegalArgumentException("Workflow $name:$version not found")
            // load and validate workflow definition
            val workflow = validation().read(workflowDefinition.definition, WorkflowFormat.YAML)
            // parse the workflow to init caches
            parseWorkflow(workflow)
            // cache and return the workflow itself
            workflow
        }

    /**
     * Gets the secrets values from environment variables based on the workflow's secrets configuration.
     * If a secret value is a valid JSON string, it will be parsed as a JSON object.
     *
     * @param workflow The workflow definition containing secrets configuration
     * @return A map of secret names to their JsonNode values from environment variables
     * @throws IllegalStateException if a required secret is not found in environment variables
     */
    fun getSecrets(workflow: Workflow): Map<String, JsonNode> =
        secretsCache.getOrPut(workflow.index) {
            workflow.use?.secrets?.associateWith { secretName ->
                val value = System.getEnv(secretName)
                    ?: throw IllegalStateException("Required secret '$secretName' not found in environment variables")
                try {
                    JsonUtils.mapper().readTree(value)
                } catch (e: Exception) {
                    JsonUtils.fromValue(value)
                }
            } ?: emptyMap()
        }

    /**
     * Retrieves a task from the workflow based on the given position.
     *
     * @param workflow The workflow definition containing the tasks.
     * @param position The JSON pointer string representing the position of the task in the workflow.
     *                 If null, defaults to "/do".
     * @return The task at the specified position.
     * @throws IllegalArgumentException if no task is found at the specified position.
     */
    fun getTask(workflow: Workflow, position: String? = null): TaskBase = (position ?: "/do").let {
        getPositionsCache(workflow)[it]
            ?: throw IllegalArgumentException("No task found at position $it for workflow ${workflow.document.name} version ${workflow.document.version}")
    }

    /**
     * Retrieves the parent task of the task at the specified position in the workflow.
     *
     * @param workflow The workflow definition containing the tasks.
     * @param position The JSON pointer string representing the position of the task in the workflow.
     * @return The parent task of the task at the specified position.
     */
    fun getParentTask(workflow: Workflow, position: String): TaskBase? =
        taskParentCache[workflow.index]?.get(position)?.let {
            getPositionsCache(workflow)[it]
        }

    fun getNextTask(workflow: Workflow, position: String): TaskBase? {
        val pos = TaskPosition.fromString(position)
        val scopedTasks = getScopedTasks(workflow, position)
        if (scopedTasks.isEmpty()) {
            val parent = taskParentCache[workflow.index]?.get(position)
            return parent?.let { getNextTask(workflow, it) }
        }
        TODO()
    }

    /**
     * Retrieves a map of scoped tasks from the workflow based on the given position.
     *
     * This method finds all tasks that are children of the same root task as the given position
     * and have the same depth as the given position.
     *
     * @param workflow The workflow definition containing the tasks.
     * @param position The JSON pointer string representing the position of the task in the workflow.
     * @return A map of task indices to their corresponding TaskBase objects.
     */
    fun getScopedTasks(workflow: Workflow, position: String): Map<Int, TaskBase> {
        val pos = TaskPosition.fromString(position)
        val parent = pos.parent

        // ensure we are on a scope
        if (parent == null || parent.depth == 0 || parent.last.toIntOrNull() == null) return emptyMap()

        val root = parent.parent ?: return emptyMap()

        return getPositionsCache(workflow)
            .keys
            .map { TaskPosition.fromString(it) }
            .filter { it.isChildOf(root) && it.depth == pos.depth }
            .associate { it.parent!!.last.toInt() to getTask(workflow, it.jsonPointer()) }
    }

    /**
     * Parses the given workflow and extracts all tasks and their positions.
     *
     * This method initializes the caches for task positions and parent tasks
     * by extracting the positions of all tasks in the workflow.
     *
     * @param workflow The workflow to be parsed.
     */
    internal fun parseWorkflow(workflow: Workflow) {
        // Map of TaskBase
        val tasks = mutableMapOf<String, TaskBase>()
        // Map of parent task pointer
        val parents = mutableMapOf<String, String?>()
        // Map of next task pointer
        val next = mutableMapOf<String, String?>()
        // Extract all tasks and their positions
        parseTask(DoTask(workflow.`do`), TaskPosition(), null, tasks, parents)
        // Initialize caches
        taskPositionsCache[workflow.index] = tasks
        taskParentCache[workflow.index] = parents
    }

    internal fun getPositionsCache(workflow: Workflow): Map<String, TaskBase> =
        taskPositionsCache[workflow.index]
            ?: throw IllegalArgumentException("No position parsed for workflow ${workflow.index}")

    private fun parseTask(
        task: TaskBase,
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        if (task !is DoTask) taskPosition.jsonPointer().let {
            tasks[it] = task
            parents[it] = taskParent
        }

        when (task) {
            is DoTask -> task.parse(taskPosition, taskParent, tasks, parents)
            is ForTask -> task.parse(taskPosition, taskParent, tasks, parents)
            is TryTask -> task.parse(taskPosition, taskParent, tasks, parents)
            is ForkTask -> task.parse(taskPosition, taskParent, tasks, parents)
            is ListenTask -> task.parse(taskPosition, taskParent, tasks, parents)
            is CallAsyncAPI -> task.parse(taskPosition, taskParent, tasks, parents)
        }
    }

    private fun DoTask.parse(
        position: TaskPosition,
        doParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val doPosition = position.addToken(DO)
        val doPos = doPosition.jsonPointer()
        tasks[doPos] = this
        parents[doPos] = doParent
        `do`.parse(doPosition, doPos, tasks, parents)
    }

    private fun ForTask.parse(
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val forPos = taskPosition.jsonPointer()
        tasks[forPos] = this
        parents[forPos] = taskParent

        DoTask(`do`).parse(taskPosition, forPos, tasks, parents)
    }

    private fun TryTask.parse(
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val tryPos = taskPosition.jsonPointer()
        tasks[tryPos] = this
        parents[tryPos] = taskParent

        val tryPosition = taskPosition.addToken(TRY)
        `try`.parse(tryPosition, tryPos, tasks, parents)

        `catch`.`do`?.let {
            val position = taskPosition.addToken(CATCH)
            DoTask(it).parse(position, tryPos, tasks, parents)
        }
    }

    private fun ForkTask.parse(
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val forPos = taskPosition.jsonPointer()
        tasks[forPos] = this
        parents[forPos] = taskParent

        fork.branches?.let {
            val position = taskPosition.addToken(FORK).addToken(BRANCHES)
            it.parse(position, forPos, tasks, parents)
        }
    }

    private fun ListenTask.parse(
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val listenPos = taskPosition.jsonPointer()
        tasks[listenPos] = this
        parents[listenPos] = taskParent

        foreach?.`do`?.let {
            val position = taskPosition.addToken(FOREACH)
            DoTask(it).parse(position, listenPos, tasks, parents)
        }
    }

    private fun CallAsyncAPI.parse(
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val callPos = taskPosition.jsonPointer()
        tasks[callPos] = this
        parents[callPos] = taskParent

        with.subscription?.foreach?.`do`?.let {
            val position = taskPosition
                .addToken(WITH)
                .addToken(SUBSCRIPTION)
                .addToken(FOREACH)
            DoTask(it).parse(position, callPos, tasks, parents)
        }
    }

    private fun List<TaskItem>.parse(
        taskPosition: TaskPosition,
        taskParent: String,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        forEachIndexed { index, taskItem ->
            parseTask(
                taskItem.toTask(),
                taskPosition.addIndex(index).addName(taskItem.name),
                taskParent,
                tasks,
                parents
            )
        }
    }


    private fun TaskItem.toTask(): TaskBase = when (val task = task.get()) {
        is TaskBase -> task
        is CallTask -> task.get() as TaskBase
        else -> throw IllegalArgumentException("Unsupported task type: ${task.javaClass.canonicalName}")
    }

    private val Workflow.index: WorkflowIndex
        get() = document.name to document.version


    companion object {
        private val logger = LoggerFactory.getLogger(this::class.java)
        internal val workflowCache = ConcurrentHashMap<WorkflowIndex, Workflow>()
        internal val taskPositionsCache = ConcurrentHashMap<WorkflowIndex, Map<String, TaskBase>>()
        internal val taskParentCache = ConcurrentHashMap<WorkflowIndex, Map<String, String?>>()
        internal val secretsCache = ConcurrentHashMap<WorkflowIndex, Map<String, JsonNode>>()
    }
}
