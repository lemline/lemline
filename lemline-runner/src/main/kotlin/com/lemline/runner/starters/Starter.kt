// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.starters

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.schemas.SchemaValidator
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.ScheduleOutboxModel
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
     * Returns a pair of [InstanceMessage] and [ScheduleOutboxModel]
     *
     * Depending on the schedule of the workflow, different messages are needed:
     *      - no schedule -> a single instanceMessage
     *      - schedule after or every -> an instanceMessage and a scheduleOutboxModel
     *      - schedule cron -> a single scheduleOutboxModel
     *      For the two last cases, we sent an IngestionMessage (first database ingestion, then only after starting the instance)
     */
    suspend fun getStartingMessages(
        workflowId: WorkflowId,
        workflowName: WorkflowName,
        optionalVersion: WorkflowVersion?,
        workflowInput: JsonElement,
        parentId: IDV7?,
        zoneId: ZoneId?,
        onError: (() -> String) -> Nothing,
    ): Pair<InstanceMessage?, ScheduleOutboxModel?> {
        // Retrieve the workflow definition from the repository
        val workflow = definitions.get(workflowName, optionalVersion)
            ?: onError { "Workflow $workflowName (version=${optionalVersion ?: "latest"}) not found." }

        val workflowVersion = WorkflowVersion(workflow.document.version)

        // Validate input against schema if any
        workflow.input?.schema?.let { schema ->
            try {
                SchemaValidator.validate(workflowInput, schema)
            } catch (e: Exception) {
                onError { "Input validation failed against workflow schema: ${e.message}." }
            }
        }

        // create the instance message, if not scheduled by a cron
        val instanceMessage = when (workflow.schedule?.cron.isNullOrBlank()) {
            true -> InstanceMessage.new(
                workflowId = workflowId,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                workflowInput = workflowInput,
                parentId = parentId,
            )

            false -> null
        }

        // create the scheduleMessage if a schedule is present
        val scheduleOutboxModel = when (workflow.schedule) {
            null -> null
            else -> ScheduleOutboxModel.from(
                workflowId = workflowId,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                workflowInput = workflowInput,
                schedule = workflow.schedule,
                zoneId = zoneId
            )
        }

        return instanceMessage to scheduleOutboxModel
    }
}
