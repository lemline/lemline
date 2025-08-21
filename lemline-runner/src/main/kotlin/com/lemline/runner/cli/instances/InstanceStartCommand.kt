// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.github.zafarkhaja.semver.Version
import com.lemline.common.json.LemlineJson
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.workflows.Workflows
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.messaging.LemlineMessage
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ScheduleRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import java.time.ZoneId
import kotlin.system.exitProcess
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@ExperimentalTime
@Unremovable
@Command(
    name = "start",
    description = ["Get specific workflow definitions, interactively if needed."],
)
class InstanceStartCommand : Runnable {

    @Mixin
    lateinit var mixin: GlobalMixin

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    @Inject
    @Channel(WORKFLOW_OUT)
    lateinit var emitter: Emitter<String>

    @Parameters(
        index = "0",
        arity = "0..1",
        description = ["Name of the workflow to start."]
    )
    var name: String? = null

    @Parameters(
        index = "1",
        arity = "0..1",
        description = ["Optional version of the workflow."]
    )
    var version: String? = null

    @Option(
        names = ["--input", "-i"],
        description = ["Input of the workflow instance (JSON format)."],
    )
    var input: String? = null

    @Option(
        names = ["--zone", "-z"],
        description = ["Timezone for workflow scheduling. Defaults to system default."],
    )
    var zone: String? = null

    override fun run() = runBlocking {
        if (name.isNullOrBlank()) cliError("Workflow name must be provided")
        val workflowName = name!!

        // Parse input JSON if provided, or use an empty object if not provided.
        val inputJsonElement = getInput(input)

        // Retrieve the workflow definition from the repository
        val workflowDefinition = getDefinition(workflowName, version)

        // Parse workflow definition into a Workflow object
        val workflow = try {
            Workflows.parse(workflowDefinition.definition)
        } catch (e: Exception) {
            cliError("Invalid workflow definition: ${e.message}")
        }

        // Validate input against schema, if the workflow has an input schema
        workflow.input?.schema?.let { schema ->
            try {
                SchemaValidator.validate(inputJsonElement, schema)
            } catch (e: Exception) {
                cliError("Input validation failed against workflow schema: ${e.message}")
            }
        }

        // create the message
        val lemlineMessage = LemlineMessage.create(
            workflowName = workflowName,
            workflowVersion = workflowDefinition.version,
            workflowInput = inputJsonElement,
            isScheduledAfter = workflow.schedule?.after != null,
        )

        // start workflow
        try {
            when (workflow.schedule) {
                null -> {
                    // Synchronously send the message to the workflow-out channel
                    emitter.send(lemlineMessage.jsonString).await()
                    println("Instance ${lemlineMessage.workflowId} started successfully (name: ${workflowDefinition.name}, version: ${workflowDefinition.version}, input: $inputJsonElement)")
                }

                else -> {
                    val zoneId = getZoneId()

                    val scheduleModel = ScheduleModel.create(
                        workflowId = lemlineMessage.workflowId,
                        workflowName = workflowName,
                        workflowVersion = workflowDefinition.version,
                        workflowInput = inputJsonElement,
                        schedule = workflow.schedule,
                        zoneId = zoneId
                    )
                    scheduleRepository.insert(scheduleModel)
                    println(
                        "Instance ${lemlineMessage.workflowId} scheduled successfully (name: ${workflowDefinition.name}, version: ${workflowDefinition.version}, input: $inputJsonElement, schedule: ${workflow.schedule}, zone: ${
                            zoneId ?: ZoneId.of("UTC")
                        })"
                    )
                }
            }
        } catch (e: Exception) {
            cliError("Failed to start workflow instance: ${e.message}")
        }
    }

    private fun getInput(input: String?): JsonElement = input?.let {
        try {
            // Parse the input string as JSON
            LemlineJson.json.parseToJsonElement(it)
        } catch (e: Exception) {
            cliError("Invalid JSON input: ${e.message}")
        }
    } ?: LemlineJson.jsonObject

    private suspend fun getDefinition(name: String, version: String?): DefinitionModel = version?.let {
        definitionRepository.findByNameAndVersion(name, it)
            ?: cliError("Workflow with name '$name' and version '$it' not found")
    } ?: run {
        val workflows = definitionRepository.listByName(name)
        if (workflows.isEmpty()) this.cliError("No workflows found with name '$name'")

        workflows.maxWithOrNull { w1, w2 ->
            runCatching {
                Version.parse(w1.version).compareTo(Version.parse(w2.version))
            }.getOrDefault(w1.version.compareTo(w2.version))
        } ?: cliError("Failed to determine latest version for workflow '$name'")
    }

    private fun getZoneId() = zone?.let {
        try {
            ZoneId.of(it)
        } catch (_: Exception) {
            cliError("Invalid timezone ID: '$it'")
        }
    }

    internal fun cliError(msg: String): Nothing {
        System.err.println(msg)
        exitProcess(1)
    }
}
