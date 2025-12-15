// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.lifecycle

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.CloudEventLifecycleHook
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.NodeStack
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement

/**
 * CDI wrapper for [CloudEventLifecycleHook].
 *
 * This class provides CDI integration for the core [CloudEventLifecycleHook] implementation.
 * If no [LifecycleEventEmitter] is configured (lifecycle events disabled), all methods become no-ops.
 *
 * @see CloudEventLifecycleHook for the actual implementation
 * @see LifecycleEventEmitter for the emission interface
 */
@ExperimentalTime
@ApplicationScoped
class LifecycleEventHookImpl(
    emitterInstance: Instance<LifecycleEventEmitter>,
) : LifecycleEventHook {

    /**
     * The delegate implementation from core, configured with the emitter if available.
     */
    private val delegate: LifecycleEventHook = run {
        val emitter: LifecycleEventEmitter? = if (emitterInstance.isResolvable) emitterInstance.get() else null
        if (emitter != null) {
            CloudEventLifecycleHook(emitter)
        } else {
            LifecycleEventHook.NOOP
        }
    }

    override suspend fun onWorkflowCreated(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
    ) = delegate.onWorkflowCreated(workflowInfo, nodeStack)

    override suspend fun onWorkflowStarted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        startedAt: Instant,
    ) = delegate.onWorkflowStarted(workflowInfo, nodeStack, startedAt)

    override suspend fun onWorkflowCompleted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        output: JsonElement,
        completedAt: Instant,
    ) = delegate.onWorkflowCompleted(workflowInfo, nodeStack, output, completedAt)

    override suspend fun onWorkflowFaulted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        error: InternalException.Error,
        failedAt: Instant,
    ) = delegate.onWorkflowFaulted(workflowInfo, nodeStack, error, failedAt)

    override suspend fun onTaskCreated(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        input: JsonElement,
        createdAt: Instant,
    ) = delegate.onTaskCreated(workflowInfo, nodeStack, nodePosition, input, createdAt)

    override suspend fun onTaskStarted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        rawInput: JsonElement,
        startedAt: Instant,
    ) = delegate.onTaskStarted(workflowInfo, nodeStack, nodePosition, rawInput, startedAt)

    override suspend fun onTaskCompleted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        output: JsonElement,
        completedAt: Instant,
    ) = delegate.onTaskCompleted(workflowInfo, nodeStack, nodePosition, output, completedAt)

    override suspend fun onTaskFaulted(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        error: InternalException.Error,
        failedAt: Instant,
    ) = delegate.onTaskFaulted(workflowInfo, nodeStack, nodePosition, error, failedAt)

    override suspend fun onTaskRetried(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        retryAt: Instant,
        attemptNumber: Int,
    ) = delegate.onTaskRetried(workflowInfo, nodeStack, nodePosition, retryAt, attemptNumber)
}
