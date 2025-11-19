// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.cli.exceptions.CliException
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.messaging.events.WorkflowEventEmitter
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.starters.Starter
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import java.time.ZoneId
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@ExperimentalTime
@ExperimentalSerializationApi
@Unremovable
@Command(
    name = "start",
    description = ["Get specific workflow definitions, interactively if needed."],
)
class InstanceStartCommand : Runnable {

    @Mixin
    lateinit var mixin: GlobalMixin

    @Inject
    lateinit var starter: Starter

    @Inject
    private lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    private lateinit var databaseEmitter: WorkflowEventEmitter

    @Inject
    private lateinit var scheduleRepository: ScheduleRepository

    @Parameters(
        index = "0",
        arity = "1",
        description = ["Namespace of the workflow to start."]
    )
    var namespace: String? = null

    @Parameters(
        index = "1",
        arity = "1",
        description = ["Name of the workflow to start."]
    )
    var name: String? = null

    @Parameters(
        index = "2",
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

    override fun run(): Unit = runBlocking {
        if (namespace.isNullOrBlank()) cliError("Workflow namespace must be provided")
        if (name.isNullOrBlank()) cliError("Workflow name must be provided")

        val workflowNamespace = WorkflowNamespace(namespace!!)
        val workflowName = WorkflowName(name!!)
        val workflowInput = getInput(input)

        val workflowId = WorkflowId.random()

        // Depending on the schedule of the workflow, different messages are needed:
        // - no schedule -> a single instanceMessage
        // - schedule after or every -> an instanceMessage and a scheduleOutboxModel
        // - schedule cron -> a single scheduleOutboxModel
        // For the two last cases, we sent an IngestionMessage (first database ingestion, then only after starting the instance)
        val (instanceMessage, scheduleOutboxModel) = starter.getStartingMessages(
            workflowId = workflowId,
            workflowNamespace = workflowNamespace,
            workflowName = workflowName,
            optionalVersion = version?.let { WorkflowVersion(it) },
            workflowInput = workflowInput,
            hasParentWaiting = false,
            zoneId = getZoneId(),
        ) { cliError(it) }

        val workflowVersion =
            instanceMessage?.workflowVersion ?: scheduleOutboxModel?.workflowVersion

        if (scheduleOutboxModel != null) {
            // Persist schedule to database (for cron or after/every schedules)
            // The schedule must be persisted before sending any instance message
            // TODO: This should ideally be transactional with message sending
            scheduleRepository.insert(scheduleOutboxModel)
        }

        // Send instance message if present (no schedule or immediate first run)
        instanceMessage?.let { instanceEmitter.send(it) }

        cliPrint("Instance $workflowId started successfully (name: $workflowName, version: $workflowVersion, input: $workflowInput)")
    }

    // Parse the input string as JSON
    private fun getInput(input: String?): JsonElement = input?.let {
        try {
            // Use Jackson first to support single quotes, unquoted field names, and comments, then normalize to JSON
            val normalized = LemlineJson.jacksonMapper.readTree(it).toString()
            LemlineJson.json.parseToJsonElement(normalized)
        } catch (e: Exception) {
            cliError("Invalid JSON input: ${e.message}")
        }
    } ?: LemlineJson.jsonObject

    private fun getZoneId() = zone?.let {
        try {
            ZoneId.of(it)
        } catch (_: Exception) {
            cliError("Invalid timezone ID: '$it'")
        }
    }

    internal fun cliPrint(msg: String) {
        println(msg)
    }

    internal fun cliError(msg: String): Nothing = throw CliException(msg)
}
