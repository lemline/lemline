// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.starters

import com.github.zafarkhaja.semver.Version
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.Definitions
import com.lemline.core.schemas.SchemaValidator
import com.lemline.runner.ingestion.IngestionMessageEmitter
import com.lemline.runner.ingestion.ScheduleIngestionMessage
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.repositories.DefinitionRepository
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
    private lateinit var definitionRepository: DefinitionRepository

    @Inject
    private lateinit var ingestionMessageEmitter: IngestionMessageEmitter

    suspend fun start(
        workflowName: WorkflowName,
        optionalVersion: WorkflowVersion?,
        workflowInput: JsonElement,
        parentId: IDV7?,
        zoneId: ZoneId?,
        onDebug: (() -> String) -> Unit,
        onError: (() -> String) -> Nothing,
    ): InstanceMessage? {
        // Retrieve the workflow definition from the repository
        val definition = getDefinition(workflowName, optionalVersion, onError)

        // Parse workflow definition into a Workflow object
        val workflow = try {
            Definitions.parse(definition)
        } catch (e: Exception) {
            onError { "Invalid workflow definition: ${e.message}" }
        }

        val workflowVersion = WorkflowVersion(workflow.document.version)

        // Validate input against schema if any
        workflow.input?.schema?.let { schema ->
            try {
                SchemaValidator.validate(workflowInput, schema)
            } catch (e: Exception) {
                onError { "Input validation failed against workflow schema: ${e.message}" }
            }
        }

        // create the message
        val workflowId = WorkflowId.random() // <- TODO create idempotent id

        val instanceMessage = InstanceMessage.new(
            workflowId = workflowId,
            workflowName = workflowName,
            workflowVersion = workflowVersion,
            workflowInput = workflowInput,
            parentId = parentId,
        )

        // start workflow
        return try {
            when (workflow.schedule) {
                null -> instanceMessage

                else -> {
                    val scheduleOutboxModel = ScheduleIngestionMessage.from(
                        workflowId = instanceMessage.workflowId,
                        workflowName = workflowName,
                        workflowVersion = workflowVersion,
                        workflowInput = workflowInput,
                        schedule = workflow.schedule,
                        zoneId = zoneId
                    )
                    ingestionMessageEmitter.send(scheduleOutboxModel)

                    // start the message right away for scheduleAfter and scheduleEvery
                    if (scheduleOutboxModel.scheduleCron != null) {
                        onDebug {
                            "Instance $workflowId scheduled successfully " +
                                "(name: $workflowName, version: $workflowVersion, input: $workflowInput, " +
                                "cron: ${workflow.schedule.cron}, zone: ${zoneId ?: ZoneId.of("UTC")}"
                        }
                        null
                    } else instanceMessage
                }
            }
        } catch (e: Exception) {
            onError { "Failed to start workflow instance: ${e.message}" }
        }
    }

    // TODO (get from cache first)
    private suspend fun getDefinition(
        workflowName: WorkflowName,
        workflowVersion: WorkflowVersion?,
        onError: (() -> String) -> Nothing
    ): String = workflowVersion?.let { version ->
        // by name and version
        definitionRepository.findByNameAndVersion(workflowName, version)
            ?.definition
            ?: onError { "Workflow with name '$workflowName' and version '$version' not found" }
    } ?: run {
        // by name only, get the last version
        val workflows = definitionRepository.listByName(workflowName)
        if (workflows.isEmpty()) onError { "No workflows found with name '$workflowName'" }

        workflows.maxWithOrNull { w1, w2 ->
            val v1 = w1.version.toString()
            val v2 = w2.version.toString()
            runCatching { Version.parse(v1).compareTo(Version.parse(v2)) }
                .getOrDefault(v1.compareTo(v2))
        }?.definition ?: onError { "Failed to determine latest version for workflow '$workflowName'" }
    }
}
