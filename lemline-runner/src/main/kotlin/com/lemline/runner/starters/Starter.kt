// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.starters

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
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

@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class Starter {

    @Inject
    private lateinit var definitions: Definitions

    /**
     * Returns a pair of [InstanceMessage] and [ScheduleModel]
     *
     * Depending on the schedule of the workflow, different messages are needed:
     *      - no schedule -> a single instanceMessage
     *      - schedule after or every -> an instanceMessage and a scheduleOutboxModel
     *      - schedule cron -> a single scheduleOutboxModel
     *      For the two last cases, we sent an IngestionMessage (first database ingestion, then only after starting the instance)
     */
    suspend fun getStartingMessages(
        workflowId: WorkflowId,
        workflowNamespace: WorkflowNamespace,
        workflowName: WorkflowName,
        optionalVersion: WorkflowVersion?,
        workflowInput: JsonElement,
        hasWaitingParent: Boolean,
        zoneId: ZoneId?,
        onError: (String) -> Nothing,
    ): Pair<InstanceMessage<WorkflowCommand.ResumeFromTask>?, ScheduleModel?> {
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

        return Pair(instanceMessage, scheduleModel)
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
