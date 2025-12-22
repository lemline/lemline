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
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.starters.PreparedWorkflow
import com.lemline.runner.common.starters.WorkflowStarter
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.schedules.ScheduleModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.ZoneId
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

@ApplicationScoped
class Starter : WorkflowStarter<ScheduleModel> {

    @Inject
    lateinit var definitions: Definitions

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
    override suspend fun prepareWorkflow(
        workflowId: WorkflowId,
        workflowNamespace: WorkflowNamespace,
        workflowName: WorkflowName,
        optionalVersion: WorkflowVersion?,
        workflowInput: JsonElement,
        hasWaitingParent: Boolean,
        zoneId: ZoneId?,
        onError: (String) -> Nothing,
    ): PreparedWorkflow<ScheduleModel> {
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
