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
        taskPositionsCache[workflow.index]?.get(it)
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
            taskPositionsCache[workflow.index]?.get(it)
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
        // Map of TaskBase per task pointer
        val tasks = mutableMapOf<String, TaskBase>()
        // Map of parent task pointer per task pointer
        val parents = mutableMapOf<String, String?>()
        // Extract all tasks and their positions
        extractTaskPosition(DoTask(workflow.`do`), TaskPosition(), null, tasks, parents)
        // Initialize caches
        taskPositionsCache[workflow.index] = tasks
        taskParentCache[workflow.index] = parents
    }

    private fun extractTaskPosition(
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
            is DoTask -> extractDoTaskPositions(task, taskPosition, taskParent, tasks, parents)
            is ForTask -> extractForTaskPositions(task, taskPosition, taskParent, tasks, parents)
            is TryTask -> extractTryTaskPositions(task, taskPosition, taskParent, tasks, parents)
            is ForkTask -> extractForkTaskPositions(task, taskPosition, taskParent, tasks, parents)
            is ListenTask -> extractListenTaskPositions(task, taskPosition, taskParent, tasks, parents)
            is CallAsyncAPI -> extractCallAsyncAPITaskPositions(task, taskPosition, taskParent, tasks, parents)
        }
    }

    private fun extractDoTaskPositions(
        task: DoTask,
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val doPosition = taskPosition.addToken(DO)
        val doPos = doPosition.jsonPointer()
        tasks[doPos] = task
        parents[doPos] = taskParent

        task.`do`.forEachIndexed { index, taskItem ->
            extractTaskPosition(
                taskItem.toTask(),
                doPosition.addIndex(index).addName(taskItem.name),
                doPos,
                tasks,
                parents
            )
        }
    }

    private fun extractForTaskPositions(
        task: ForTask,
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val forPos = taskPosition.jsonPointer()
        tasks[forPos] = task
        parents[forPos] = taskParent

        task.`do`?.let {
            val doPosition = taskPosition.addToken(DO)
            DoTask(it).extract(doPosition, forPos, tasks, parents)
        }
    }

    private fun extractTryTaskPositions(
        task: TryTask,
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val tryPos = taskPosition.jsonPointer()
        tasks[tryPos] = task
        parents[tryPos] = taskParent

        task.`try`.let {
            val tryPosition = taskPosition
                .addToken(TRY)
            it.extract(tryPosition, tryPos, tasks, parents)
        }
        task.catch?.`do`?.let {
            val doPosition = taskPosition
                .addToken(CATCH)
                .addToken(DO)
            DoTask(it).extract(doPosition, tryPos, tasks, parents)
        }
    }

    private fun extractForkTaskPositions(
        task: ForkTask,
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val forPos = taskPosition.jsonPointer()
        tasks[forPos] = task
        parents[forPos] = taskParent

        task.fork.branches.let {
            val position = taskPosition
                .addToken(FORK)
                .addToken(BRANCHES)
            it.extract(position, forPos, tasks, parents)
        }
    }

    private fun extractListenTaskPositions(
        task: ListenTask,
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val listenPos = taskPosition.jsonPointer()
        tasks[listenPos] = task
        parents[listenPos] = taskParent

        task.foreach?.`do`?.let {
            val doPosition = taskPosition
                .addToken(FOREACH)
                .addToken(DO)
            DoTask(it).extract(doPosition, listenPos, tasks, parents)
        }
    }


    private fun extractCallAsyncAPITaskPositions(
        task: CallAsyncAPI,
        taskPosition: TaskPosition,
        taskParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val callPos = taskPosition.jsonPointer()
        tasks[callPos] = task
        parents[callPos] = taskParent

        task.with.subscription?.foreach?.`do`?.let {
            val doPosition = taskPosition
                .addToken(WITH)
                .addToken(SUBSCRIPTION)
                .addToken(FOREACH)
                .addToken(DO)
            DoTask(it).extract(doPosition, callPos, tasks, parents)
        }
    }

    private fun List<TaskItem>.extract(
        taskPosition: TaskPosition,
        taskParent: String,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        forEachIndexed { index, taskItem ->
            extractTaskPosition(
                taskItem.toTask(),
                taskPosition.addIndex(index).addName(taskItem.name),
                taskParent,
                tasks,
                parents
            )
        }
    }

    private fun DoTask.extract(
        doPosition: TaskPosition,
        doParent: String?,
        tasks: MutableMap<String, TaskBase>,
        parents: MutableMap<String, String?>
    ) {
        val doPos = doPosition.jsonPointer()
        tasks[doPos] = this
        parents[doPos] = doParent
        `do`.extract(doPosition, doPos, tasks, parents)
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