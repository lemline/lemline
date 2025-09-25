// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.activities.runs.getInputFor
import com.lemline.core.errors.WorkflowException
import com.lemline.core.instances.RunInstance
import com.lemline.core.instances.TryInstance
import com.lemline.core.instances.WaitInstance
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.processor.Processor
import com.lemline.runner.exceptions.RunWorkflowStartedException
import com.lemline.runner.exceptions.TaskCompletedException
import com.lemline.runner.exceptions.TaskRetriedException
import com.lemline.runner.exceptions.WaitStartedException
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.database.CompletedMessage
import com.lemline.runner.messaging.database.DatabaseMessageEmitter
import com.lemline.runner.messaging.database.IngestionMessage
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.starters.Starter
import io.quarkus.runtime.Startup
import io.serverlessworkflow.api.types.RunWorkflow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

/**
 * WorkflowConsumer is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@Startup
@ApplicationScoped
internal class StepByStepRunner @Inject constructor(
    private val databaseEmitter: DatabaseMessageEmitter,
    private val stater: Starter
) {
    val logger = logger()

    private val taskCompletedHandler = { task: NodeInstance<*> ->
        if (task.node.isActivity()) throw TaskCompletedException()
    }

    private val taskStartedHandler = { task: NodeInstance<*> ->
        when (task) {
            is WaitInstance -> if (task.delay.isPositive()) throw WaitStartedException(task.delay)
            is RunInstance -> if (task.node.task.run.get() is RunWorkflow) throw RunWorkflowStartedException(
                task.node.task.run.get() as RunWorkflow
            )

            else -> Unit
        }
    }

    private val taskRetriedHandler = { t: TryInstance, e: WorkflowException ->
        throw TaskRetriedException(t, e)
    }

    suspend fun InstanceMessage.run(processor: Processor): InstanceMessage? {

        processor.onTaskCompleted(taskCompletedHandler)
        processor.onTaskStarted(taskStartedHandler)
        processor.onTaskRetried(taskRetriedHandler)

        val nextMessage = try {
            processor.run()
            onWorkflowCompleted(processor.getOutput(), processor.isScheduledAfter)
            null
        } catch (_: TaskCompletedException) {
            logger.debug { "Task completed (${processor.position})" }
            // next message
            updateFrom(processor)
        } catch (e: TaskRetriedException) {
            logger.debug { "Scheduling retry of task (${processor.position})" }
            // Store the message to the retry repository
            updateFrom(processor).onRetry(e.tryInstance, e.cause)
            null
        } catch (e: WaitStartedException) {
            logger.debug { "Starting wait task (${processor.position})" }
            // Store the message to the wait repository
            updateFrom(processor).onWait(e.delay)
            null
        } catch (e: RunWorkflowStartedException) {
            logger.debug { "Starting child workflow (${processor.position})" }
            // Store the message to the run workflow repository
            updateFrom(processor).onRunWorkflow(e.runWorkflow, processor.current as RunInstance)
        }

        return nextMessage
    }

    /**
     * Handles the execution of a child workflow.
     * This method inserts records for the parent workflow (without schedule ) into the `RunWorkflowRepository`.
     * The child workflow is started right after
     */
    private suspend fun InstanceMessage.onRunWorkflow(
        runWorkflow: RunWorkflow,
        runInstance: RunInstance
    ): InstanceMessage? {
        // insert the parent workflow without delayedUntil
        // TODO make id idempotent
        val parentOutboxModel = ParentOutboxModel(
            id = IDV7.random(),
            instanceMessage = this,
            outboxScheduledFor = null,
        )

        val (instanceMessage, scheduleOutboxModel) = stater.getStartingMessages(
            workflowId = WorkflowId.random(),
            workflowNamespace = WorkflowNamespace(runWorkflow.workflow.namespace),
            workflowName = WorkflowName(runWorkflow.workflow.name),
            optionalVersion = WorkflowVersion(runWorkflow.workflow.version),
            workflowInput = runWorkflow.getInputFor(runInstance),
            parentId = parentOutboxModel.id,
            zoneId = null
        ) { error(it) }

        // As we already have a ParentOutboxModel, we always send an IngestionMessage
        // The instance will be started only after the parent (and possible schedule) ingestion
        val ingestionMessage = IngestionMessage(
            instanceModels = listOfNotNull(parentOutboxModel, scheduleOutboxModel),
            instanceMessages = listOfNotNull(instanceMessage)
        )

        databaseEmitter.send(ingestionMessage)

        return null
    }

    /**
     * Handles the completion of the workflow instance.
     */
    private suspend fun InstanceMessage.onWorkflowCompleted(output: JsonElement, isScheduledAfter: Boolean) {
        if (parentId != null || isScheduledAfter) {
            val completedMessage = CompletedMessage(
                workflowId = workflowId,
                workflowNamespace = workflowNamespace,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                parentId = parentId,
                output = if (parentId != null) output else null,
                isScheduledAfter = isScheduledAfter
            )
            databaseEmitter.send(completedMessage)
        }
    }

    /**
     * Handles the retry mechanism for the workflow instance by saving a RetryModel message to the retry repository
     *
     * The workflow instance's processing is halted temporarily, and further processing is expected to resume
     * asynchronously through the RetryOutbox.
     */
    private suspend fun InstanceMessage.onRetry(tryInstance: TryInstance, e: WorkflowException) {
        val delay = tryInstance.delay ?: error("No delay set in in $tryInstance")

        val retryMessage = RetryOutboxModel.from(
            id = IDV7.random(),
            instance = this,
            outboxScheduledFor = Clock.System.now().plus(delay),
            error = e,
            reason = getFailureReason(e)
        )
        // Send the message to ingest into the retry table
        databaseEmitter.send(IngestionMessage(retryMessage))
    }

    /**
     * Handles the "wait" state of the workflow instance by saving a WaitModel message to the wait repository.
     *
     * The workflow instance's processing is halted temporarily, and further processing is expected to resume
     * asynchronously through the WaitOutbox.
     */
    private suspend fun InstanceMessage.onWait(delay: Duration) {
        val waitMessage = WaitOutboxModel(
            id = IDV7.random(),
            instanceMessage = this,
            outboxScheduledFor = Clock.System.now().plus(delay),
        )
        // Send the message to ingest into the wait table
        databaseEmitter.send(IngestionMessage(waitMessage))
    }
}
