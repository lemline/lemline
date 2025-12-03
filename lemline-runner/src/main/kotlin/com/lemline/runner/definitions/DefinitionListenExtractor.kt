// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token.CATCH
import com.lemline.common.values.Token.DO
import com.lemline.common.values.Token.FORK
import com.lemline.common.values.Token.TRY
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.common.values.name
import com.lemline.common.values.namespace
import com.lemline.common.values.version
import com.lemline.core.processors.ListenStrategy
import com.lemline.runner.models.DefinitionListenFilterModel
import com.lemline.runner.models.DefinitionListenModel
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy
import io.serverlessworkflow.api.types.CallTask
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.EventFilter
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.ListenTaskConfiguration
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import io.serverlessworkflow.api.types.OneEventConsumptionStrategy
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Result of extracting listen tasks from a workflow definition.
 *
 * @property listenTask The listen task definition
 * @property filters The event filters for the listen task
 */
@ExperimentalTime
data class ExtractedListenTask(
    val listenTask: DefinitionListenModel,
    val filters: List<DefinitionListenFilterModel>
)

/**
 * Extracts listen task configurations from workflow definitions.
 *
 * When a workflow definition containing listen tasks is registered, this extractor
 * traverses the workflow tree and creates [DefinitionListenModel] entries for
 * each listen task, along with [DefinitionListenFilterModel] entries for each filter.
 *
 * ## Strategy Handling
 *
 * - **ONE**: Creates one listen task with one filter
 * - **ANY**: Creates one listen task with filters per entry (or one wildcard filter for `any: []`)
 * - **ALL**: Creates one listen task with one filter per entry with `filterIndex` for tracking
 */
@ExperimentalTime
@ApplicationScoped
class DefinitionListenExtractor {

    private val logger = logger()

    /**
     * Extracts all listen tasks and their filters from a workflow definition.
     *
     * @param workflow The parsed workflow definition
     * @return List of [ExtractedListenTask] entries for storage
     */
    fun extract(workflow: Workflow): List<ExtractedListenTask> {
        val results = mutableListOf<ExtractedListenTask>()

        val namespace = workflow.namespace
        val name = workflow.name
        val version = workflow.version

        // Traverse the workflow's do block
        workflow.`do`?.let { taskItems ->
            val doPosition = NodePosition.root.addToken(DO)
            traverseTaskItems(taskItems, doPosition, namespace, name, version, results)
        }

        logger.debug { "Extracted ${results.size} listen tasks from workflow ${workflow.document.name}" }
        return results
    }

    /**
     * Converts a TaskItem to its underlying TaskBase.
     */
    private fun TaskItem.toTask(): TaskBase = when (val task = task.get()) {
        is TaskBase -> task
        is CallTask -> task.get() as TaskBase
        else -> throw IllegalArgumentException("Unsupported task type: ${task.javaClass.canonicalName}")
    }

    /**
     * Traverses a list of task items recursively to find listen tasks.
     */
    private fun traverseTaskItems(
        taskItems: List<TaskItem>,
        parentPosition: NodePosition,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        results: MutableList<ExtractedListenTask>
    ) {
        taskItems.forEach { taskItem ->
            val taskName = taskItem.name
            val task = taskItem.toTask()
            val position = parentPosition.addName(taskName)

            when (task) {
                is ListenTask -> extractListenTask(task, position, namespace, name, version, results)
                is DoTask -> task.`do`?.let {
                    traverseTaskItems(it, position.addToken(DO), namespace, name, version, results)
                }

                is ForTask -> task.`do`?.let {
                    traverseTaskItems(it, position.addToken(DO), namespace, name, version, results)
                }

                is TryTask -> {
                    task.`try`?.let {
                        traverseTaskItems(it, position.addToken(TRY), namespace, name, version, results)
                    }
                    task.catch?.`do`?.let {
                        traverseTaskItems(it, position.addToken(CATCH), namespace, name, version, results)
                    }
                }

                is ForkTask -> task.fork?.branches?.forEach { branchItem ->
                    val branchTask = branchItem.toTask()
                    val branchPosition = position.addToken(FORK).addName(branchItem.name)
                    when (branchTask) {
                        is ListenTask -> extractListenTask(
                            branchTask,
                            branchPosition,
                            namespace,
                            name,
                            version,
                            results
                        )

                        is DoTask -> branchTask.`do`?.let {
                            traverseTaskItems(it, branchPosition.addToken(DO), namespace, name, version, results)
                        }
                        // Other nested task types in branches
                    }
                }
            }
        }
    }

    /**
     * Extracts a listen task and its filters.
     */
    private fun extractListenTask(
        task: ListenTask,
        position: NodePosition,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        results: MutableList<ExtractedListenTask>
    ) {
        val listenConfig = task.listen ?: return
        val listenTo = listenConfig.to ?: return

        val listenId = IDV7.random()
        val readAs = listenConfig.read ?: ListenAndReadAs.DATA

        when (val strategy = listenTo.get()) {
            is OneEventConsumptionStrategy -> {
                val filters = mutableListOf<DefinitionListenFilterModel>()
                strategy.one?.let { filter ->
                    filters.add(createFilterModel(filter, 0, listenId))
                }
                results.add(
                    ExtractedListenTask(
                        listenTask = createListenModel(listenId, namespace, name, version, position, ListenStrategy.ONE, readAs),
                        filters = filters
                    )
                )
            }

            is AnyEventConsumptionStrategy -> {
                val eventFilters = strategy.any
                val filters = if (eventFilters.isNullOrEmpty()) {
                    // Wildcard mode: any: []
                    listOf(createWildcardFilterModel(listenId))
                } else {
                    eventFilters.mapIndexed { index, filter ->
                        createFilterModel(filter, index, listenId)
                    }
                }
                results.add(
                    ExtractedListenTask(
                        listenTask = createListenModel(listenId, namespace, name, version, position, ListenStrategy.ANY, readAs),
                        filters = filters
                    )
                )
            }

            is AllEventConsumptionStrategy -> {
                val filters = strategy.all?.mapIndexed { index, filter ->
                    createFilterModel(filter, index, listenId)
                } ?: emptyList()
                results.add(
                    ExtractedListenTask(
                        listenTask = createListenModel(listenId, namespace, name, version, position, ListenStrategy.ALL, readAs),
                        filters = filters
                    )
                )
            }

            else -> logger.warn { "Unknown listen strategy type at $position: ${strategy?.javaClass?.name}" }
        }
    }

    /**
     * Creates a DefinitionListenModel for a listen task.
     */
    private fun createListenModel(
        id: IDV7,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        position: NodePosition,
        strategy: ListenStrategy,
        readAs: ListenAndReadAs
    ): DefinitionListenModel = DefinitionListenModel(
        id = id,
        workflowNamespace = namespace,
        workflowName = name,
        workflowVersion = version,
        nodePosition = position,
        strategy = strategy,
        readAs = readAs
    )

    /**
     * Creates a DefinitionListenFilterModel from an EventFilter.
     */
    private fun createFilterModel(
        filter: EventFilter,
        filterIndex: Int,
        listenId: IDV7
    ): DefinitionListenFilterModel {
        val eventProps = filter.with

        // Extract correlation patterns as JSON
        val correlations = filter.correlate?.additionalProperties?.let { props ->
            if (props.isEmpty()) null
            else {
                buildJsonObject {
                    props.forEach { (key, value) ->
                        put(key, buildJsonObject {
                            put("from", value.from)
                            value.expect?.let { put("expect", it) }
                        })
                    }
                }.let { Json.encodeToString(it) }
            }
        }

        return DefinitionListenFilterModel(
            id = IDV7.random(),
            listenId = listenId,
            filterIndex = filterIndex,
            eventId = eventProps?.id,
            eventType = eventProps?.type,
            eventSource = eventProps?.source?.get()?.toString(),
            eventSubject = eventProps?.subject,
            eventDatacontenttype = eventProps?.datacontenttype,
            eventDataschema = eventProps?.dataschema?.get()?.toString(),
            eventTime = eventProps?.time?.get()?.toString(),
            eventData = eventProps?.data?.get()?.toString(),
            correlations = correlations
        )
    }

    /**
     * Creates a wildcard DefinitionListenFilterModel (for `any: []`).
     */
    private fun createWildcardFilterModel(listenId: IDV7): DefinitionListenFilterModel = DefinitionListenFilterModel(
        id = IDV7.random(),
        listenId = listenId,
        filterIndex = 0,
        eventId = null,
        eventType = null,
        eventSource = null,
        eventSubject = null,
        eventDatacontenttype = null,
        eventDataschema = null,
        eventTime = null,
        eventData = null,
        correlations = null
    )
}
