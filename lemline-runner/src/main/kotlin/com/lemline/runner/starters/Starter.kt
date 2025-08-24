package com.lemline.runner.starters

import com.github.zafarkhaja.semver.Version
import com.lemline.common.ids.IdGenerator
import com.lemline.core.definitions.Definitions
import com.lemline.core.schemas.SchemaValidator
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ScheduleRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.ZoneId
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
@ApplicationScoped
class Starter {
    @Inject
    private lateinit var definitionRepository: DefinitionRepository

    @Inject
    private lateinit var scheduleRepository: ScheduleRepository

    suspend fun start(
        workflowName: String,
        optionalVersion: String?,
        workflowInput: JsonElement,
        parentId: String?,
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

        val workflowVersion = workflow.document.version

        // Validate input against schema if any
        workflow.input?.schema?.let { schema ->
            try {
                SchemaValidator.validate(workflowInput, schema)
            } catch (e: Exception) {
                onError { "Input validation failed against workflow schema: ${e.message}" }
            }
        }

        // create the message
        val workflowId = IdGenerator.generateTimeBasedId() // <- TODO create idempotent id

        val instanceMessage = InstanceMessage.forNewWorkflow(
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
                    val scheduleModel = ScheduleModel.create(
                        workflowId = instanceMessage.workflowId,
                        workflowName = workflowName,
                        workflowVersion = workflowVersion,
                        workflowInput = workflowInput,
                        schedule = workflow.schedule,
                        zoneId = zoneId
                    )
                    scheduleRepository.insert(scheduleModel)
                    // start the message right away for scheduleAfter and scheduleEvery
                    if (scheduleModel.scheduleCron != null) {
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

    private suspend fun getDefinition(name: String, version: String?, onError: (() -> String) -> Nothing): String =
        version?.let { version ->
            // by name and version
            definitionRepository.findByNameAndVersion(name, version)?.definition
                ?: onError { "Workflow with name '$name' and version '$version' not found" }
        } ?: run {
            // by name only, get the last version
            val workflows = definitionRepository.listByName(name)
            if (workflows.isEmpty()) onError { "No workflows found with name '$name'" }

            workflows.maxWithOrNull { w1, w2 ->
                runCatching { Version.parse(w1.version).compareTo(Version.parse(w2.version)) }
                    .getOrDefault(w1.version.compareTo(w2.version))
            }?.definition ?: onError { "Failed to determine latest version for workflow '$name'" }
        }
}
