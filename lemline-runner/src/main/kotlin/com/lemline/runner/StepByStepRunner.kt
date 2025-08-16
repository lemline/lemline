// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.common.debug
import com.lemline.common.ids.IdGenerator
import com.lemline.common.logger
import com.lemline.core.activities.runs.getInputFor
import com.lemline.core.instances.RunInstance
import com.lemline.core.instances.TryInstance
import com.lemline.core.instances.WaitInstance
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.workflows.WorkflowInstance
import com.lemline.runner.exceptions.RunWorkflowStartedException
import com.lemline.runner.exceptions.TaskCompletedException
import com.lemline.runner.exceptions.TaskRetriedException
import com.lemline.runner.exceptions.WaitStartedException
import com.lemline.runner.messaging.MessageBody
import com.lemline.runner.messaging.toMessage
import com.lemline.runner.models.RetryModel
import com.lemline.runner.models.RunWorkflowModel
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.RunWorkflowRepository
import com.lemline.runner.repositories.WaitRepository
import io.quarkus.runtime.Startup
import io.serverlessworkflow.api.types.RunWorkflow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * WorkflowConsumer is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel.
 */
@OptIn(ExperimentalTime::class)
@Startup
@ApplicationScoped
internal class StepByStepRunner @Inject constructor(
    private val retryRepository: RetryRepository,
    private val waitRepository: WaitRepository,
    private val runWorkflowRepository: RunWorkflowRepository,
) {
    private val logger = logger()

    private val onTaskCompleted = { task: NodeInstance<*> ->
        if (task.node.isActivity()) throw TaskCompletedException()
    }

    private val onTaskStarted = { task: NodeInstance<*> ->
        when (task) {
            is WaitInstance -> if (task.delay.isPositive()) throw WaitStartedException(task.delay)
            is RunInstance -> if (task.node.task.run.get() is RunWorkflow) throw RunWorkflowStartedException(
                task.node.task.run.get() as RunWorkflow
            )

            else -> Unit
        }
    }

    suspend fun run(instance: WorkflowInstance): MessageBody? {

        instance.onTaskCompleted { onTaskCompleted(it) }
        instance.onTaskStarted { onTaskStarted(it) }
        instance.onTaskRetried { throw TaskRetriedException() }

        val nextMessage = try {
            instance.run()
            instance.onWorkflowCompleted()
            null
        } catch (_: TaskCompletedException) {
            logger.debug { "Task completed at ${instance.position}" }
            // next message
            instance.toMessage()
        } catch (_: TaskRetriedException) {
            logger.debug { "Task retried at ${instance.position}" }
            // Store the message to the retry repository
            instance.onRetry()
            null
        } catch (e: WaitStartedException) {
            logger.debug { "Task waiting at ${instance.position}" }
            // Store the message to the wait repository
            instance.onWait(e.delay)
            null
        } catch (e: RunWorkflowStartedException) {
            logger.debug { "run Workflow at ${instance.position}" }
            // Store the message to the run workflow repository
            instance.onRunWorkflow(e.runWorkflow)
            null
        }

        return nextMessage
    }

    /**
     * Handles the execution of a child workflow.
     * This method inserts records for both the parent workflow (waiting state) and the
     * child workflow (running state) into the `RunWorkflowRepository` within the same transaction.
     * The parent workflow is marked as waiting, and the child workflow is initialized with
     * a running state and other relevant attributes.
     *
     * @param runWorkflow The workflow execution details, including the workflow name, version,
     *                    input parameters, and configuration for whether the parent waits.
     * @return Always returns null to indicate that the processing of the current workflow instance
     *         has been stopped post-handling of the child workflow setup.
     */
    private suspend fun WorkflowInstance.onRunWorkflow(runWorkflow: RunWorkflow) {

        runWorkflowRepository.withTransaction { connection ->
            // in the same transaction!

            // insert the parent workflow (waiting) without delayedUntil
            runWorkflowRepository.insert(
                RunWorkflowModel(
                    workflowId = id,
                    workflowName = name,
                    workflowVersion = version,
                    workflowPosition = position!!.toString(),
                    workflowState = state.toJsonString()
                ),
                connection
            )

            // insert the child workflow (running) to start right away
            val childId = IdGenerator.generateTimeBasedId()

            val child = MessageBody.newInstance(
                id = childId,
                name = runWorkflow.workflow.name,
                version = runWorkflow.workflow.version,
                input = runWorkflow.getInputFor(current as RunInstance),
                parentId = id,
                parentIsWaiting = runWorkflow.isAwait

            )
            runWorkflowRepository.insert(
                RunWorkflowModel(
                    workflowId = childId,
                    workflowName = child.workflowName,
                    workflowVersion = child.workflowVersion,
                    workflowPosition = child.workflowPosition.serialized,
                    workflowState = child.workflowState.serialized,
                    outboxScheduledFor = Clock.System.now(),
                ),
                connection
            )
        }
    }

    /**
     * Handles the completion of the workflow instance. This method finalizes the processing of
     * the workflow by performing the necessary cleanup or post-processing actions as applicable.
     * Once this method is called, no further processing will occur for the current workflow instance.
     */
    private suspend fun WorkflowInstance.onWorkflowCompleted() {
        if (parent?.isWaiting == true) {
            // if there is an error when retrieving the parent, the MessageConsumer will mark the message as failed
            val entity: RunWorkflowModel = runWorkflowRepository.findById(parent!!.workflowId)!!
            // Get current state of the parent workflow
            val state = entity.state
            // set the workflow output at the rawOutput at the current position of the parent workflow
            state[entity.position]!!.rawOutput = getOutput()
            // by adding a delayedUntil value, the outbox pattern will send the message asap to restart the parent workflow
            runWorkflowRepository.update(
                entity.copy(
                    outboxDelayedUntil = Clock.System.now(),
                    workflowState = state.toJsonString()
                )
            )
            logger.debug { "Restarting parent workflow:\n${entity.toMessageBody().jsonPrettyString}" }
        }
    }

    /**
     * Handles the retry mechanism for the workflow instance. This method calculates the delay duration
     * specified in the workflow instance, determines the delayed execution time, and stores this information
     * along with the serialized message in the retry repository. The workflow instance processing is then
     * halted temporarily, with the expectation that further processing will be resumed asynchronously
     * via an external mechanism like an outbox system.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun WorkflowInstance.onRetry() {
        val delay = (current as TryInstance).delay ?: error("No delay set in for $this")

        val retryModel = RetryModel(
            workflowId = id,
            workflowName = name,
            workflowVersion = version,
            workflowPosition = position.toString(),
            workflowState = state.toJsonString(),
            message = null,
            outboxDelayedUntil = Clock.System.now().plus(delay)
        )
        // Save the message to the retry table
        retryRepository.insert(retryModel)
    }

    /**
     * Handles the "wait" state of the workflow instance by saving a wait message to the wait repository.
     * The method calculates the delay duration specified in the workflow instance, determines the delayed
     * execution time, and stores this information along with the serialized message in the wait repository.
     * The workflow instance's processing is halted temporarily, and further processing is expected to resume
     * asynchronously through an external mechanism such as an outbox system.
     */
    private suspend fun WorkflowInstance.onWait(delay: Duration) {
        val waitModel = WaitModel(
            workflowId = id,
            workflowName = name,
            workflowVersion = version,
            workflowPosition = position!!.toString(),
            workflowState = state.toJsonString(),
            outboxDelayedUntil = Clock.System.now().plus(delay),
        )
        // Save the message to the wait table
        waitRepository.insert(waitModel)
    }
}
