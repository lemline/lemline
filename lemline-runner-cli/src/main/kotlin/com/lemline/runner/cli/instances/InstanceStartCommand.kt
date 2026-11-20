// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.lifecycle.CreationEventEnqueuer
import com.lemline.runner.common.lifecycle.CreationEventOutbox
import kotlin.time.Clock
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.cli.exceptions.CliException
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.starters.Starter
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

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
    internal lateinit var commandEmitter: CommandEmitter

    @Inject
    internal lateinit var scheduleRepository: ScheduleRepository

    @Inject
    internal lateinit var databaseConfig: DatabaseConfig

    @Inject
    internal lateinit var creationEventEnqueuer: CreationEventEnqueuer

    @Inject
    internal lateinit var creationEventOutbox: CreationEventOutbox

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
        val preparedWorkflow = starter.prepareWorkflow(
            workflowId = workflowId,
            workflowNamespace = workflowNamespace,
            workflowName = workflowName,
            optionalVersion = version?.let { WorkflowVersion(it) },
            workflowInput = workflowInput,
            hasWaitingParent = false,
            zoneId = getZoneId(),
        ) { cliError(it) }

        val instanceMessage = preparedWorkflow.instanceMessage
        val scheduleOutboxModel = preparedWorkflow.scheduleModel

        val workflowVersion =
            instanceMessage?.workflowVersion ?: scheduleOutboxModel?.workflowVersion

        // Accept the instance in one transaction: the schedule (cron or after/every), the creation
        // lifecycle events, and the command. The command is published before the commit, so a
        // failed publication rolls everything back; once committed, the creation events are
        // relayed by this process below, or later by any running Lemline process.
        val creationEvents = databaseConfig.withTransaction { conn ->
            scheduleOutboxModel?.let { scheduleRepository.insert(it, conn) }
            instanceMessage?.let { prepared ->
                val accepted = prepared.copy(workflowState = prepared.workflowState.copy(createdAt = Clock.System.now()))
                val ids = creationEventEnqueuer.enqueueCreation(accepted, conn)
                commandEmitter.send(accepted)
                ids
            } ?: emptyList()
        }

        // Short-lived process: publish the creation events now and wait for the broker.
        creationEventOutbox.relayNow(creationEvents)

        cliPrint("Instance $workflowId started successfully (name: $workflowName, version: $workflowVersion, input: $workflowInput)")
    }

    // Parse the input string as JSON
    private fun getInput(input: String?): JsonNode = input?.let {
        try {
            // Use Jackson first to support single quotes, unquoted field names, and comments, then normalize to JSON
            val normalized = LemlineJson.jacksonMapper.readTree(it).toString()
            parseJson(normalized)
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

    fun cliPrint(msg: String) {
        println(msg)
    }

    fun cliError(msg: String): Nothing = throw CliException(msg)
}
