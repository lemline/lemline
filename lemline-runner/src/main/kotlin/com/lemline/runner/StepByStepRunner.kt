// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.ids.IdGenerator
import com.lemline.common.logger
import com.lemline.core.activities.runs.getInputFor
import com.lemline.core.instances.RunInstance
import com.lemline.core.instances.TryInstance
import com.lemline.core.instances.WaitInstance
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.processor.Processor
import com.lemline.runner.exceptions.RunWorkflowStartedException
import com.lemline.runner.exceptions.TaskCompletedException
import com.lemline.runner.exceptions.TaskRetriedException
import com.lemline.runner.exceptions.WaitStartedException
import com.lemline.runner.ingestion.IngestionMessageEmitter
import com.lemline.runner.ingestion.RetryIngestionMessage
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.repositories.PARENT_TABLE
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.SCHEDULE_TABLE
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WaitRepository
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
    private val waitRepository: WaitRepository,
    private val parentRepository: ParentRepository,
    private val scheduleRepository: ScheduleRepository,
    private val ingestionEmitter: IngestionMessageEmitter,
    private val stater: Starter
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

    suspend fun run(processor: Processor): InstanceMessage? {

        processor.onTaskCompleted { onTaskCompleted(it) }
        processor.onTaskStarted { onTaskStarted(it) }
        processor.onTaskRetried { throw TaskRetriedException() }

        val nextMessage = try {
            processor.run()
            processor.onWorkflowCompleted(
                processor.getOutput(),
                processor.workflow.schedule?.after != null
            )
            null
        } catch (_: TaskCompletedException) {
            logger.debug { "Task completed (${processor.position})" }
            // next message
            processor.updatedInstanceMessage
        } catch (_: TaskRetriedException) {
            logger.debug { "Scheduling retry of task (${processor.position})" }
            // Store the message to the retry repository
            processor.updatedInstanceMessage.onRetry(processor.current as TryInstance)
            null
        } catch (e: WaitStartedException) {
            logger.debug { "Starting wait task (${processor.position})" }
            // Store the message to the wait repository
            processor.updatedInstanceMessage.onWait(e.delay)
            null
        } catch (e: RunWorkflowStartedException) {
            logger.debug { "Starting child workflow (${processor.position})" }
            // Store the message to the run workflow repository
            processor.updatedInstanceMessage.onRunWorkflow(e.runWorkflow, processor.current as RunInstance)
        }

        return nextMessage?.also { it.message = processor.updatedInstanceMessage.message }
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
        val parent = ParentOutboxModel(
            instance = this,
            outboxScheduledFor = null,
        )
        parentRepository.insert(parent)

        return stater.start(
            workflowName = runWorkflow.workflow.name,
            optionalVersion = runWorkflow.workflow.version,
            workflowInput = runWorkflow.getInputFor(runInstance),
            parentId = parent.id,
            zoneId = null,
            onDebug = { fn -> logger.debug { fn() } },
            onError = { fn -> error(fn()) },
        )
    }

    /**
     * Handles the completion of the workflow instance.
     */
    private suspend fun Processor.onWorkflowCompleted(output: JsonElement, isScheduledAfter: Boolean) =
        with(instance as InstanceMessage) {
            // Case of child workflow completion
            parentId?.let { parentId ->
                // if there is an error when retrieving the parent, the MessageConsumer will mark the message as failed
                parentRepository.findById(parentId)?.let { parent ->
                    // updates the parent with the output at the current position
                    parent.completeWith(output)
                    parentRepository.update(parent)
                    logger.debug { "Parent workflow ${parent.workflowId} (${parent.workflowName} of workflow $workflowId ($workflowName), set up to restart at position ${parent.workflowPosition} with output $output" }
                }
                    ?: logger.error { "CRITICAL - Unable to find parent $parentId of workflow $workflowId ($workflowName) in $PARENT_TABLE table. The parent workflow will not be restarted." }
            }

            // Case of workflow completion with scheduled after
            if (isScheduledAfter) {
                scheduleRepository.findByWorkflowId(workflowId)?.let { schedule ->
                    // updates the scheduled execution instant from the after property.
                    schedule.scheduleAfterCompletion()
                    scheduleRepository.update(schedule)
                    logger.debug { "Rescheduled workflow ${schedule.workflowName} for ${schedule.outboxScheduledFor}" }
                }
                    ?: logger.error { "CRITICAL - Unable to find workflow $workflowId ($workflowName) in $SCHEDULE_TABLE table. The workflow will not be restarted." }
            }
        }

    /**
     * Handles the retry mechanism for the workflow instance by saving a RetryModel message to the retry repository
     *
     * The workflow instance's processing is halted temporarily, and further processing is expected to resume
     * asynchronously through the RetryOutbox.
     */
    private suspend fun InstanceMessage.onRetry(tryInstance: TryInstance) {
        val delay = tryInstance.delay ?: error("No delay set in in $tryInstance")

        val retryMessage = RetryIngestionMessage(
            id = IdGenerator.generateUUIDV7(),
            instance = this,
            outboxScheduledFor = Clock.System.now().plus(delay),
        )
        // Save the message to ingest into the retry table
        ingestionEmitter.send(retryMessage)
    }

    /**
     * Handles the "wait" state of the workflow instance by saving a WaitModel message to the wait repository.
     *
     * The workflow instance's processing is halted temporarily, and further processing is expected to resume
     * asynchronously through the WaitOutbox.
     */
    private suspend fun InstanceMessage.onWait(delay: Duration) {
        val waitOutboxModel = WaitOutboxModel(
            instance = this,
            outboxScheduledFor = Clock.System.now().plus(delay),
        )
        // Save the message to the wait table
        waitRepository.insert(waitOutboxModel)
    }

    private val Processor.updatedInstanceMessage
        get() = (instance as InstanceMessage).updateWith(state, position!!)
}
