// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.common.debug
import com.lemline.common.error
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
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.PARENT_TABLE
import com.lemline.runner.models.ParentModel
import com.lemline.runner.models.RetryModel
import com.lemline.runner.models.SCHEDULE_TABLE
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.ScheduleRepository
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
    private val parentRepository: ParentRepository,
    private val scheduleRepository: ScheduleRepository,
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

    suspend fun InstanceMessage.run(workflowInstance: WorkflowInstance): InstanceMessage? {

        workflowInstance.onTaskCompleted { onTaskCompleted(it) }
        workflowInstance.onTaskStarted { onTaskStarted(it) }
        workflowInstance.onTaskRetried { throw TaskRetriedException() }

        val nextMessage = try {
            workflowInstance.run()
            onWorkflowCompleted(workflowInstance)
            null
        } catch (_: TaskCompletedException) {
            logger.debug { "Task completed at ${workflowInstance.position}" }
            // next message
            updateWith(workflowInstance.state, workflowInstance.position)
        } catch (_: TaskRetriedException) {
            logger.debug { "Task retried at ${workflowInstance.position}" }
            // Store the message to the retry repository
            onRetry(workflowInstance)
            null
        } catch (e: WaitStartedException) {
            logger.debug { "Task waiting at ${workflowInstance.position}" }
            // Store the message to the wait repository
            onWait(workflowInstance, e.delay)
            null
        } catch (e: RunWorkflowStartedException) {
            logger.debug { "run Workflow at ${workflowInstance.position}" }
            // Store the message to the run workflow repository
            onRunWorkflow(workflowInstance, e.runWorkflow)
        }

        return nextMessage?.also { it.message = message }
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
    private suspend fun InstanceMessage.onRunWorkflow(
        workflowInstance: WorkflowInstance,
        runWorkflow: RunWorkflow
    ): InstanceMessage {

        // insert the parent workflow (waiting) without delayedUntil
        val parent = ParentModel(
            instance = updateWith(workflowInstance.state, workflowInstance.position),
            outboxScheduledFor = null,
        )
        parentRepository.insert(parent)

        // return this message to start the child workflow right away
        return InstanceMessage.forNewWorkflow(
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowInput = runWorkflow.getInputFor(workflowInstance.current as RunInstance),
            parentId = workflowId,
            scheduleId = null,
        )
    }


    /**
     * Handles the completion of the workflow instance. This method finalizes the processing of
     * the workflow by performing the necessary cleanup or post-processing actions as applicable.
     * Once this method is called, no further processing will occur for the current workflow instance.
     */
    private suspend fun InstanceMessage.onWorkflowCompleted(workflowInstance: WorkflowInstance) {

        // Case of child workflow completion
        parentId?.let { parentId ->
            // if there is an error when retrieving the parent, the MessageConsumer will mark the message as failed
            parentRepository.findById(parentId)?.let { parent ->
                // Get the current state of the parent workflow
                val state = parent.workflowState
                // set the workflow output at the rawOutput at the current position of the parent workflow
                val output = workflowInstance.getOutput()
                state[parent.workflowPosition]!!.rawOutput = output
                // Set delayedUntil to restart parent workflow via the ParentOutbox
                parent.outboxDelayedUntil = Clock.System.now()
                // save the updated parent model
                parentRepository.update(parent)
                logger.debug { "Parent workflow ${parent.workflowId} (${parent.workflowName} of workflow $workflowId ($workflowName), set up to restart at position ${parent.workflowPosition} with output $output" }
            }
                ?: logger.error { "CRITICAL - Unable to find parent $parentId of workflow $workflowId ($workflowName) in $PARENT_TABLE table. The parent workflow will not be restarted." }
        }

        // Case of workflow completion with scheduled after
        scheduleId?.let { scheduleId ->
            scheduleRepository.findById(scheduleId)?.let { schedule ->
                schedule.rescheduleAfterCompletion()
                scheduleRepository.update(schedule)
                logger.debug { "Rescheduled workflow ${schedule.instance.workflowName} for ${schedule.outboxScheduledFor}" }
            }
                ?: logger.error { "CRITICAL - Unable to find schedule $scheduleId of workflow $workflowId ($workflowName) in $SCHEDULE_TABLE table. The workflow will not be restarted." }
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
    private suspend fun InstanceMessage.onRetry(workflowInstance: WorkflowInstance) {
        val delay = (workflowInstance.current as TryInstance).delay ?: error("No delay set in for $this")

        val retryModel = RetryModel(
            instance = updateWith(workflowInstance.state, workflowInstance.position),
            outboxScheduledFor = Clock.System.now().plus(delay),
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
    private suspend fun InstanceMessage.onWait(workflowInstance: WorkflowInstance, delay: Duration) {
        val waitModel = WaitModel(
            instance = updateWith(workflowInstance.state, workflowInstance.position),
            outboxScheduledFor = Clock.System.now().plus(delay),
        )
        // Save the message to the wait table
        waitRepository.insert(waitModel)
    }
}
