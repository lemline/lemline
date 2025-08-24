// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.lemline.common.json.LemlineJson
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.starters.Starter
import io.quarkus.arc.Unremovable
import io.quarkus.smallrye.reactivemessaging.sendSuspending
import jakarta.inject.Inject
import java.time.ZoneId
import kotlin.system.exitProcess
import kotlin.time.ExperimentalTime
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
    lateinit var stater: Starter

    @Inject
    @Channel(WORKFLOW_OUT)
    private lateinit var emitter: Emitter<String>

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

    override fun run(): Unit = runBlocking {
        if (name.isNullOrBlank()) cliError { "Workflow name must be provided" }
        val workflowName = name!!
        val workflowInput = getInput(input)

        val instanceMessage = stater.start(
            workflowName = workflowName,
            optionalVersion = version,
            workflowInput = workflowInput,
            parentId = null,
            zoneId = getZoneId(),
            ::cliPrint,
            ::cliError
        )
        instanceMessage?.let {
            emitter.sendSuspending(it.payload)
            cliPrint {
                "Instance ${it.workflowId} scheduled successfully (name: $workflowName, version: ${it.workflowVersion}, input: $workflowInput)"
            }
        }

    }

    // Parse the input string as JSON
    private fun getInput(input: String?): JsonElement = input?.let {
        try {
            LemlineJson.json.parseToJsonElement(it)
        } catch (e: Exception) {
            cliError { "Invalid JSON input: ${e.message}" }
        }
    } ?: LemlineJson.jsonObject

    private fun getZoneId() = zone?.let {
        try {
            ZoneId.of(it)
        } catch (_: Exception) {
            cliError { "Invalid timezone ID: '$it'" }
        }
    }

    internal fun cliPrint(msg: () -> String) {
        println(msg())
    }

    internal fun cliError(msg: () -> String): Nothing {
        System.err.println(msg())
        exitProcess(1)
    }
}
