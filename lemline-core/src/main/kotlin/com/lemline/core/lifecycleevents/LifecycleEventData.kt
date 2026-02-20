// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.lifecycleevents

import com.lemline.common.values.WorkflowInfo
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Data payloads for Serverless Workflow lifecycle events.
 *
 * These classes represent the `data` field of CloudEvents emitted for workflow
 * and task lifecycle transitions. The structure follows the Serverless Workflow
 * lifecycle events specification.
 *
 * @see LifecycleEventHook for event emission hook points
 */
@Serializable
sealed class LifecycleEventData {

    // ==========================================
    // Workflow Event Data
    // ==========================================

    /**
     * Data payload for workflow.created event.
     *
     * This event is emitted when a workflow instance is created (before execution starts).
     * It represents the intent to start a workflow, not actual execution.
     *
     * @property name Qualified workflow name (namespace/name:version)
     * @property input The workflow input data
     * @property createdAt Workflow creation timestamp
     * @property definition Workflow definition metadata
     */
    @Serializable
    @SerialName("workflowCreated")
    data class WorkflowCreatedData(
        val name: String,
        val input: JsonElement,
        val createdAt: Instant,
        val definition: WorkflowDefinitionData,
    ) : LifecycleEventData()

    /**
     * Data payload for workflow.started event.
     *
     * @property name Qualified workflow name (namespace/name:version)
     * @property startedAt Workflow start timestamp
     * @property definition Workflow definition metadata
     */
    @Serializable
    @SerialName("workflowStarted")
    data class WorkflowStartedData(
        val name: String,
        val startedAt: Instant,
        val definition: WorkflowDefinitionData,
    ) : LifecycleEventData()

    /**
     * Data payload for workflow.completed event.
     *
     * @property name Qualified workflow name
     * @property output Workflow output data
     * @property completedAt Workflow completion timestamp
     */
    @Serializable
    @SerialName("workflowCompleted")
    data class WorkflowCompletedData(
        val name: String,
        val output: JsonElement,
        val completedAt: Instant,
    ) : LifecycleEventData()

    /**
     * Data payload for workflow.faulted event.
     *
     * @property name Qualified workflow name
     * @property faultedAt Fault timestamp
     * @property error Error information
     */
    @Serializable
    @SerialName("workflowFaulted")
    data class WorkflowFaultedData(
        val name: String,
        val faultedAt: Instant,
        val error: ErrorInfo,
    ) : LifecycleEventData()

    // ==========================================
    // Task Event Data
    // ==========================================

    /**
     * Data payload for task.created event.
     *
     * This event is emitted when a task is scheduled/queued for execution,
     * before it actually starts running.
     *
     * @property workflow Qualified workflow name
     * @property task JSON Pointer to task in workflow definition
     * @property input Task input data
     * @property createdAt Task creation/scheduling timestamp
     */
    @Serializable
    @SerialName("taskCreated")
    data class TaskCreatedData(
        val workflow: String,
        val task: String,
        val input: JsonElement,
        val createdAt: Instant,
    ) : LifecycleEventData()

    /**
     * Data payload for task.started event.
     *
     * @property workflow Qualified workflow name
     * @property task JSON Pointer to task in workflow definition
     * @property startedAt Task start timestamp
     */
    @Serializable
    @SerialName("taskStarted")
    data class TaskStartedData(
        val workflow: String,
        val task: String,
        val startedAt: Instant,
    ) : LifecycleEventData()

    /**
     * Data payload for task.completed event.
     *
     * @property workflow Qualified workflow name
     * @property task JSON Pointer to task
     * @property output Task output data
     * @property completedAt Task completion timestamp
     */
    @Serializable
    @SerialName("taskCompleted")
    data class TaskCompletedData(
        val workflow: String,
        val task: String,
        val output: JsonElement,
        val completedAt: Instant,
    ) : LifecycleEventData()

    /**
     * Data payload for task.faulted event.
     *
     * @property workflow Qualified workflow name
     * @property task JSON Pointer to task
     * @property faultedAt Fault timestamp
     * @property error Error information
     */
    @Serializable
    @SerialName("taskFaulted")
    data class TaskFaultedData(
        val workflow: String,
        val task: String,
        val faultedAt: Instant,
        val error: ErrorInfo,
    ) : LifecycleEventData()

    /**
     * Data payload for task.retried event.
     *
     * @property workflow Qualified workflow name
     * @property task JSON Pointer to task
     * @property retriedAt Retry timestamp
     * @property retryCount Current retry attempt number (1-based)
     */
    @Serializable
    @SerialName("taskRetried")
    data class TaskRetriedData(
        val workflow: String,
        val task: String,
        val retriedAt: Instant,
        val retryCount: Int,
    ) : LifecycleEventData()
}

/**
 * Workflow definition metadata included in workflow lifecycle events.
 *
 * This is a flattened representation of [WorkflowInfo] with readable JSON field names
 * for CloudEvent data payloads.
 *
 * @property namespace Workflow namespace
 * @property name Workflow name (without namespace)
 * @property version Workflow version
 */
@Serializable
data class WorkflowDefinitionData(
    val namespace: String,
    val name: String,
    val version: String,
) {
    companion object {
        /**
         * Creates a [WorkflowDefinitionData] from a [WorkflowInfo].
         */
        fun from(info: WorkflowInfo) = WorkflowDefinitionData(
            namespace = info.namespace.toString(),
            name = info.name.toString(),
            version = info.version.toString(),
        )
    }
}

/**
 * Error information included in faulted events.
 *
 * Follows RFC 7807 Problem Details structure.
 *
 * @property type Error type URI
 * @property status HTTP-like status code
 * @property title Human-readable error title
 */
@Serializable
data class ErrorInfo(
    val type: String,
    val status: Int,
    val title: String,
)
