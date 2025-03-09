package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.repositories.WorkflowDefinitionRepository
import com.lemline.swruntime.system.System
import com.lemline.swruntime.tasks.TaskPosition
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
    private val workflowDefinitionRepository: WorkflowDefinitionRepository
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
            // validate workflow definition, cache and return it
            validation().read(workflowDefinition.definition, WorkflowFormat.YAML)
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
    fun getTask(workflow: Workflow, position: String? = null): TaskBase = with(position ?: "/do") {
        getTaskPositions(workflow)[this]
            ?: throw IllegalArgumentException("No task found at position $this for workflow ${workflow.document.name} version ${workflow.document.version}")
    }

    /**
     * Extracts all tasks and their positions from a workflow definition.
     *
     * This method traverses the workflow definition and builds a map of JSON pointers to tasks.
     * The JSON pointers represent the path to each task in the workflow structure.
     *
     * For example, a task at "/do/0" represents the first task in the main workflow sequence.
     * Nested tasks will have longer paths like "/do/1/do/0" for a task inside a branch.
     *
     * @param workflow The workflow definition to extract tasks from
     * @return A map of JSON pointer strings to the corresponding task objects
     */
    internal fun getTaskPositions(workflow: Workflow): Map<String, TaskBase> =
        taskPositionsCache.getOrPut(workflow.index) {
            val tasks = mutableMapOf<String, TaskBase>()
            extractTaskPositions(DoTask(workflow.`do`), TaskPosition(), tasks)
            tasks
        }

    private fun extractTaskPositions(
        task: TaskBase,
        taskPosition: TaskPosition,
        allTasks: MutableMap<String, TaskBase>
    ) {
        if (task !is DoTask) allTasks[taskPosition.jsonPointer()] = task

        when (task) {
            is DoTask -> extractDoTaskPositions(task, taskPosition, allTasks)
            is ForTask -> extractForTaskPositions(task, taskPosition, allTasks)
            is TryTask -> extractTryTaskPositions(task, taskPosition, allTasks)
            is ForkTask -> extractForkTaskPositions(task, taskPosition, allTasks)
            is ListenTask -> extractListenTaskPositions(task, taskPosition, allTasks)
            is CallAsyncAPI -> extractCallAsyncAPITaskPositions(task, taskPosition, allTasks)
        }
    }

    private fun extractDoTaskPositions(
        task: DoTask,
        taskPosition: TaskPosition,
        allTasks: MutableMap<String, TaskBase>
    ) {
        val position = taskPosition.addProperty("do")
        allTasks[position.jsonPointer()] = task
        task.`do`.forEachIndexed { index, taskItem ->
            extractTaskPositions(
                taskItem.toTask(),
                position.addIndex(index).addProperty(taskItem.name),
                allTasks
            )
        }
    }

    private fun extractForTaskPositions(
        task: ForTask,
        taskPosition: TaskPosition,
        allTasks: MutableMap<String, TaskBase>
    ) {
        task.`do`?.let {
            val doPosition = taskPosition.addProperty("do")
            allTasks[doPosition.jsonPointer()] = DoTask(it)
            it.forEachIndexed { index, taskItem ->
                extractTaskPositions(
                    taskItem.toTask(),
                    doPosition.addIndex(index).addProperty(taskItem.name),
                    allTasks
                )
            }
        }
    }

    private fun extractTryTaskPositions(
        task: TryTask,
        taskPosition: TaskPosition,
        allTasks: MutableMap<String, TaskBase>
    ) {
        task.`try`.let {
            val tryPosition = taskPosition.addProperty("try")
            it.forEachIndexed { index, taskItem ->
                extractTaskPositions(
                    taskItem.toTask(),
                    tryPosition.addIndex(index).addProperty(taskItem.name),
                    allTasks
                )
            }
        }
        task.catch?.`do`?.let {
            val catchPosition = taskPosition
                .addProperty("catch")
                .addProperty("do")
            allTasks[catchPosition.jsonPointer()] = DoTask(it)
            it.forEachIndexed { index, taskItem ->
                extractTaskPositions(
                    taskItem.toTask(),
                    catchPosition.addIndex(index).addProperty(taskItem.name),
                    allTasks
                )
            }
        }
    }

    private fun extractForkTaskPositions(
        task: ForkTask,
        taskPosition: TaskPosition,
        allTasks: MutableMap<String, TaskBase>
    ) {
        task.fork.branches.let {
            val position = taskPosition
                .addProperty("fork")
                .addProperty("branches")
            it.forEachIndexed { index, taskItem ->
                extractTaskPositions(
                    taskItem.toTask(),
                    position.addIndex(index).addProperty(taskItem.name),
                    allTasks
                )
            }
        }
    }

    private fun extractListenTaskPositions(
        task: ListenTask,
        taskPosition: TaskPosition,
        allTasks: MutableMap<String, TaskBase>
    ) {
        task.foreach?.`do`?.let {
            val position = taskPosition
                .addProperty("foreach")
                .addProperty("do")
            allTasks[position.jsonPointer()] = DoTask(it)
            it.forEachIndexed { index, taskItem ->
                extractTaskPositions(
                    taskItem.toTask(),
                    position.addIndex(index).addProperty(taskItem.name),
                    allTasks
                )
            }
        }
    }

    private fun extractCallAsyncAPITaskPositions(
        task: CallAsyncAPI,
        taskPosition: TaskPosition,
        allTasks: MutableMap<String, TaskBase>
    ) {
        task.with.subscription?.foreach?.`do`?.let {
            val position = taskPosition
                .addProperty("with")
                .addProperty("subscription")
                .addProperty("foreach")
                .addProperty("do")
            allTasks[position.jsonPointer()] = DoTask(it)
            it.forEachIndexed { index, taskItem ->
                extractTaskPositions(
                    taskItem.toTask(),
                    position.addIndex(index).addProperty(taskItem.name),
                    allTasks
                )
            }
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
        private val workflowCache = ConcurrentHashMap<WorkflowIndex, Workflow>()
        private val taskPositionsCache = ConcurrentHashMap<WorkflowIndex, Map<String, TaskBase>>()
        private val secretsCache = ConcurrentHashMap<WorkflowIndex, Map<String, JsonNode>>()
    }
} 