// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.lifecycle

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.ErrorInfo
import com.lemline.core.lifecycleevents.LifecycleEventData
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.lifecycleevents.WorkflowDefinitionData
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import io.cloudevents.core.builder.CloudEventBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Implementation of [LifecycleEventHook] that builds CloudEvents and emits them
 * via [LifecycleEventEmitter].
 *
 * This class is responsible for:
 * - Building CloudEvents with proper structure and Lemline extensions
 * - Generating deterministic event IDs for idempotency
 * - Extracting workflow metadata from WorkflowInfo and NodeStack
 * - Delegating emission to [LifecycleEventEmitter]
 *
 * This bean is always available. If lifecycle events are disabled (no emitter configured),
 * all methods become no-ops.
 */
@ExperimentalTime
@ApplicationScoped
class LifecycleEventHookImpl(
    private val emitterInstance: Instance<LifecycleEventEmitter>,
) : LifecycleEventHook {

    /**
     * The emitter if available, null otherwise (lifecycle events disabled).
     */
    private val emitter: LifecycleEventEmitter? by lazy {
        if (emitterInstance.isResolvable) emitterInstance.get() else null
    }

    private val json = Json { encodeDefaults = true }

    // ==========================================
    // Workflow Lifecycle Events
    // ==========================================

    override suspend fun onWorkflowCreated(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack.rootState

        val data = LifecycleEventData.WorkflowCreatedData(
            name = workflowInfo.qualifiedName,
            input = rootState.workflowInput,
            createdAt = rootState.startedAt,
            definition = WorkflowDefinitionData.from(workflowInfo),
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.WORKFLOW_CREATED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = rootState.startedAt,
            idSuffix = "workflow-created",
        )

        emitter.emit(event)
    }

    override suspend fun onWorkflowStarted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        startedAt: Instant,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack[NodePosition.root] as RootState

        val data = LifecycleEventData.WorkflowStartedData(
            name = workflowInfo.qualifiedName,
            startedAt = startedAt,
            definition = WorkflowDefinitionData.from(workflowInfo),
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.WORKFLOW_STARTED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = startedAt,
            idSuffix = "workflow-started",
        )

        emitter.emit(event)
    }

    override suspend fun onWorkflowCompleted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        output: JsonElement,
        completedAt: Instant,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack[NodePosition.root] as RootState

        val data = LifecycleEventData.WorkflowCompletedData(
            name = workflowInfo.qualifiedName,
            output = output,
            completedAt = completedAt,
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.WORKFLOW_COMPLETED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = completedAt,
            idSuffix = "workflow-completed",
        )

        emitter.emit(event)
    }

    override suspend fun onWorkflowFaulted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        error: InternalException.Error,
        failedAt: Instant,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack[NodePosition.root] as RootState

        val data = LifecycleEventData.WorkflowFaultedData(
            name = workflowInfo.qualifiedName,
            faultedAt = failedAt,
            error = error.toErrorInfo(),
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.WORKFLOW_FAULTED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = failedAt,
            idSuffix = "workflow-faulted",
        )

        emitter.emit(event)
    }

    // ==========================================
    // Task Lifecycle Events
    // ==========================================

    override suspend fun onTaskCreated(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        input: JsonElement,
        createdAt: Instant,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack[NodePosition.root] as RootState

        val data = LifecycleEventData.TaskCreatedData(
            workflow = workflowInfo.qualifiedName,
            task = nodePosition.toJsonPointer(),
            input = input,
            createdAt = createdAt,
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.TASK_CREATED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = createdAt,
            idSuffix = "task-created-${nodePosition.toIdSuffix()}",
        )

        emitter.emit(event)
    }

    override suspend fun onTaskStarted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        rawInput: JsonElement,
        startedAt: Instant,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack[NodePosition.root] as RootState

        val data = LifecycleEventData.TaskStartedData(
            workflow = workflowInfo.qualifiedName,
            task = nodePosition.toJsonPointer(),
            startedAt = startedAt,
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.TASK_STARTED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = startedAt,
            idSuffix = "task-started-${nodePosition.toIdSuffix()}",
        )

        emitter.emit(event)
    }

    override suspend fun onTaskCompleted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        output: JsonElement,
        completedAt: Instant,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack[NodePosition.root] as RootState

        val data = LifecycleEventData.TaskCompletedData(
            workflow = workflowInfo.qualifiedName,
            task = nodePosition.toJsonPointer(),
            output = output,
            completedAt = completedAt,
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.TASK_COMPLETED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = completedAt,
            idSuffix = "task-completed-${nodePosition.toIdSuffix()}",
        )

        emitter.emit(event)
    }

    override suspend fun onTaskFaulted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        error: InternalException.Error,
        failedAt: Instant,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack[NodePosition.root] as RootState

        val data = LifecycleEventData.TaskFaultedData(
            workflow = workflowInfo.qualifiedName,
            task = nodePosition.toJsonPointer(),
            faultedAt = failedAt,
            error = error.toErrorInfo(),
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.TASK_FAULTED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = failedAt,
            idSuffix = "task-faulted-${nodePosition.toIdSuffix()}",
        )

        emitter.emit(event)
    }

    override suspend fun onTaskRetried(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        retryAt: Instant,
        attemptNumber: Int,
    ) {
        val emitter = this.emitter ?: return
        val rootState = nodeStack[NodePosition.root] as RootState

        val data = LifecycleEventData.TaskRetriedData(
            workflow = workflowInfo.qualifiedName,
            task = nodePosition.toJsonPointer(),
            retriedAt = retryAt,
            retryCount = attemptNumber,
        )

        val event = buildCloudEvent(
            eventType = LifecycleEventType.TASK_RETRIED,
            workflowInfo = workflowInfo,
            workflowId = rootState.workflowId,
            nodeStack = nodeStack,
            data = data,
            timestamp = retryAt,
            idSuffix = "task-retried-${nodePosition.toIdSuffix()}-$attemptNumber",
        )

        emitter.emit(event)
    }

    // ==========================================
    // CloudEvent Building
    // ==========================================

    /**
     * Builds a CloudEvent with Lemline extensions.
     */
    private fun buildCloudEvent(
        eventType: LifecycleEventType,
        workflowInfo: WorkflowInfo,
        workflowId: WorkflowId,
        nodeStack: NodeStack,
        data: LifecycleEventData,
        timestamp: Instant,
        idSuffix: String,
    ): io.cloudevents.CloudEvent {
        val rootState = nodeStack[NodePosition.root] as RootState
        val eventId = deriveEventId(workflowId, rootState.workflowStep, idSuffix)
        val sourceUri =
            URI.create("urn:lemline:workflow:${workflowInfo.namespace}:${workflowInfo.name}:${workflowInfo.version}")

        return CloudEventBuilder.v1()
            .withId(eventId)
            .withSource(sourceUri)
            .withType(eventType.type)
            .withTime(timestamp.toOffsetDateTime())
            .withDataContentType("application/json")
            .withData("application/json", json.encodeToString(data).toByteArray())
            // Lemline extension attributes
            .withExtension("lemlineworkflowid", workflowId.toString())
            .withExtension("lemlineworkflownamespace", workflowInfo.namespace.toString())
            .withExtension("lemlineworkflowname", workflowInfo.name.toString())
            .withExtension("lemlineworkflowversion", workflowInfo.version.toString())
            .build()
    }

    /**
     * Derives a deterministic event ID for idempotency.
     *
     * The ID is derived from workflow instance, step, and event type to ensure
     * the same event produces the same ID on replay.
     */
    private fun deriveEventId(
        workflowId: WorkflowId,
        workflowStep: Int,
        idSuffix: String,
    ): String {
        // Use a deterministic format: workflowId-step-suffix
        // This ensures idempotent event IDs for replay scenarios
        return "$workflowId-$workflowStep-$idSuffix"
    }

    /**
     * Converts NodePosition to a JSON Pointer string.
     */
    private fun NodePosition.toJsonPointer(): String {
        // NodePosition.toString() returns the path which is already a JSON Pointer
        return toString()
    }

    /**
     * Converts NodePosition to a safe ID suffix (replaces / with -)
     */
    private fun NodePosition.toIdSuffix(): String {
        // Replace / with - for use in IDs
        return toString().replace("/", "-").trimStart('-')
    }

    /**
     * Converts InternalException.Error to ErrorInfo for CloudEvent data.
     */
    private fun InternalException.Error.toErrorInfo(): ErrorInfo {
        return ErrorInfo(
            type = type,
            status = status,
            title = title ?: "Unknown error",
        )
    }

    /**
     * Converts Kotlin Instant to Java OffsetDateTime in UTC.
     */
    private fun Instant.toOffsetDateTime(): OffsetDateTime {
        return OffsetDateTime.ofInstant(this.toJavaInstant(), ZoneOffset.UTC)
    }
}
