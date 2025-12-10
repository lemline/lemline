// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)

package com.lemline.runner.starters

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.ScheduleModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.ZoneId
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

/**
 * A prepared workflow ready to be dispatched.
 *
 * Contains all components needed to start a workflow instance:
 * - The instance message to send to the workflow channel
 * - Optional schedule model for scheduled workflows
 * - Lifecycle hook callback to emit workflow.created event
 *
 * @property instanceMessage The instance message to send (null for cron-scheduled workflows)
 * @property scheduleModel The schedule model for scheduled workflows (null if no schedule)
 * @property onWorkflowCreated Lambda to trigger the workflow.created lifecycle event.
 *           Caller should invoke this after successfully sending the message.
 */
data class PreparedWorkflow(
    val instanceMessage: InstanceMessage<WorkflowCommand.ResumeFromTask>?,
    val scheduleModel: ScheduleModel?,
    val onWorkflowCreated: suspend (LifecycleEventHook) -> Unit,
)

@ApplicationScoped
class Starter {

    @Inject
    private lateinit var definitions: Definitions

    /**
     * Returns [PreparedWorkflow] containing the instance message, schedule model, and lifecycle hook lambda.
     *
     * Depending on the schedule of the workflow, different messages are needed:
     *      - no schedule -> a single instanceMessage
     *      - schedule after or every -> an instanceMessage and a scheduleOutboxModel
     *      - schedule cron -> a single scheduleOutboxModel
     *      For the two last cases, we sent an IngestionMessage (first database ingestion, then only after starting the instance)
     *
     * The [PreparedWorkflow.onWorkflowCreated] lambda should be called by the caller after successfully
     * sending the message to emit the workflow.created lifecycle event.
     */
    suspend fun prepareWorkflow(
        workflowId: WorkflowId,
        workflowNamespace: WorkflowNamespace,
        workflowName: WorkflowName,
        optionalVersion: WorkflowVersion?,
        workflowInput: JsonElement,
        hasWaitingParent: Boolean,
        zoneId: ZoneId?,
        onError: (String) -> Nothing,
    ): PreparedWorkflow {
        // Retrieve the workflow definition from the repository
        val workflow = definitions.get(workflowNamespace, workflowName, optionalVersion)
            ?: onError("Workflow $workflowName (version=${optionalVersion ?: "latest"}) not found.")

        val workflowVersion = WorkflowVersion(workflow.document.version)

        // Validate input against schema if any
        validateInput(workflowInput, workflow, onError)

        // create the instance message, if not scheduled by a cron
        val instanceMessage = when (workflow.schedule?.cron.isNullOrBlank()) {
            true -> InstanceMessage(
                workflowInfo = WorkflowInfo(workflowNamespace, workflowName, workflowVersion),
                workflowState = StepByStepOrchestrator.initCmd(workflowId, workflowInput, hasWaitingParent)
            )

            false -> null
        }

        // create the scheduleMessage if a schedule is present
        val scheduleModel = when (workflow.schedule) {
            null -> null
            else -> ScheduleModel.from(
                workflowId = workflowId,
                workflowNamespace = workflowNamespace,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                workflowInput = workflowInput,
                schedule = workflow.schedule,
                zoneId = zoneId
            )
        }

        // Create the lambda to trigger the workflow.created lifecycle event
        // Caller invokes this after successfully sending the message
        // Note: For cron-scheduled workflows (no instanceMessage), the lambda is a no-op
        val workflowInfo = WorkflowInfo(workflowNamespace, workflowName, workflowVersion)
        val onWorkflowCreated: suspend (LifecycleEventHook) -> Unit = { hook ->
            instanceMessage?.let { msg ->
                hook.onWorkflowCreated(workflowInfo, msg.workflowState.nodeStack)
            }
        }

        return PreparedWorkflow(instanceMessage, scheduleModel, onWorkflowCreated)
    }

    private fun validateInput(
        workflowInput: JsonElement,
        workflow: io.serverlessworkflow.api.types.Workflow,
        onError: (String) -> Nothing,
    ) {
        workflow.input?.schema?.let { schema ->
            try {
                SchemaValidator.validate(workflowInput, schema)
            } catch (e: Exception) {
                onError("Input validation failed against workflow schema: ${e.message}.")
            }
        }
    }
}
