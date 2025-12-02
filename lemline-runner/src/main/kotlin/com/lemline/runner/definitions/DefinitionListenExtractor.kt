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
import com.lemline.runner.models.DefinitionListenModel
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy
import io.serverlessworkflow.api.types.CallTask
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.EventFilter
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.OneEventConsumptionStrategy
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Extracts listen task filter configurations from workflow definitions.
 *
 * When a workflow definition containing listen tasks is registered, this extractor
 * traverses the workflow tree and creates [DefinitionListenModel] entries for
 * each filter in each listen task.
 *
 * ## Strategy Handling
 *
 * - **ONE**: Creates one entry for the single filter
 * - **ANY**: Creates one entry per filter (or one wildcard entry for `any: []`)
 * - **ALL**: Creates one entry per filter with `filterIndex` for tracking
 */
@ExperimentalTime
@ApplicationScoped
class DefinitionListenExtractor {

    private val logger = logger()

    /**
     * Extracts all listen task filters from a workflow definition.
     *
     * @param workflow The parsed workflow definition
     * @return List of [DefinitionListenModel] entries for storage
     */
    fun extract(workflow: Workflow): List<DefinitionListenModel> {
        val results = mutableListOf<DefinitionListenModel>()

        val namespace = WorkflowNamespace(workflow.document.namespace)
        val name = WorkflowName(workflow.document.name)
        val version = WorkflowVersion(workflow.document.version)

        // Traverse the workflow's do block
        workflow.`do`?.let { taskItems ->
            val doPosition = NodePosition.root.addToken(DO)
            traverseTaskItems(taskItems, doPosition, namespace, name, version, results)
        }

        logger.debug { "Extracted ${results.size} listen filters from workflow ${workflow.document.name}" }
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
        results: MutableList<DefinitionListenModel>
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
                        is ListenTask -> extractListenTask(branchTask, branchPosition, namespace, name, version, results)
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
     * Extracts filter entries from a listen task.
     */
    private fun extractListenTask(
        task: ListenTask,
        position: NodePosition,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        results: MutableList<DefinitionListenModel>
    ) {
        val listenConfig = task.listen ?: return
        val listenTo = listenConfig.to ?: return

        when (val strategy = listenTo.get()) {
            is OneEventConsumptionStrategy -> {
                strategy.one?.let { filter ->
                    results.add(createModelFromFilter(filter, 0, position, namespace, name, version))
                }
            }

            is AnyEventConsumptionStrategy -> {
                val filters = strategy.any
                if (filters.isNullOrEmpty()) {
                    // Wildcard mode: any: []
                    results.add(createWildcardModel(position, namespace, name, version))
                } else {
                    filters.forEachIndexed { index, filter ->
                        results.add(createModelFromFilter(filter, index, position, namespace, name, version))
                    }
                }
            }

            is AllEventConsumptionStrategy -> {
                strategy.all?.forEachIndexed { index, filter ->
                    results.add(createModelFromFilter(filter, index, position, namespace, name, version))
                }
            }

            else -> logger.warn { "Unknown listen strategy type at $position: ${strategy?.javaClass?.name}" }
        }
    }

    /**
     * Creates a DefinitionListenModel from an EventFilter.
     */
    private fun createModelFromFilter(
        filter: EventFilter,
        filterIndex: Int,
        position: NodePosition,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): DefinitionListenModel {
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

        return DefinitionListenModel(
            id = IDV7.random(),
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            nodePosition = position,
            filterIndex = filterIndex,
            eventType = eventProps?.type,
            eventSource = eventProps?.source?.get()?.toString(),
            eventSubject = eventProps?.subject,
            correlations = correlations
        )
    }

    /**
     * Creates a wildcard DefinitionListenModel (for `any: []`).
     */
    private fun createWildcardModel(
        position: NodePosition,
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): DefinitionListenModel = DefinitionListenModel(
        id = IDV7.random(),
        workflowNamespace = namespace,
        workflowName = name,
        workflowVersion = version,
        nodePosition = position,
        filterIndex = 0,
        eventType = null,
        eventSource = null,
        eventSubject = null,
        correlations = null
    )
}
