// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.starters

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
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

@ApplicationScoped
class Starter : WorkflowStarter<ScheduleModel> {

    @Inject
    lateinit var definitions: Definitions

    /**
     * Returns [PreparedWorkflow] containing the instance message and schedule model.
     *
     * Depending on the schedule of the workflow, different messages are needed:
     *      - no schedule -> a single instanceMessage
     *      - schedule after or every -> an instanceMessage and a scheduleOutboxModel
     *      - schedule cron -> a single scheduleOutboxModel
     *      For the two last cases, we sent an IngestionMessage (first database ingestion, then only after starting the instance)
     *
     * The caller enqueues the creation lifecycle events when it accepts the instance message.
     */
    override suspend fun prepareWorkflow(
        workflowId: WorkflowId,
        workflowNamespace: WorkflowNamespace,
        workflowName: WorkflowName,
        optionalVersion: WorkflowVersion?,
        workflowInput: JsonNode,
        hasWaitingParent: Boolean,
        zoneId: ZoneId?,
        onError: (String) -> Nothing,
    ): PreparedWorkflow<ScheduleModel> {
        // Retrieve the workflow definition from the repository
        val workflow = definitions.get(workflowNamespace, workflowName, optionalVersion)
            ?: onError("Workflow $workflowName (version=${optionalVersion ?: "latest"}) not found.")

        val workflowVersion = WorkflowVersion(workflow.document.version)

        if (workflow.schedule?.on != null) {
            onError("Workflow $workflowNamespace/$workflowName:$workflowVersion uses schedule.on and starts automatically from matching CloudEvents; it cannot be started manually.")
        }

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

        return PreparedWorkflow(instanceMessage, scheduleModel)
    }

    private fun validateInput(
        workflowInput: JsonNode,
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
