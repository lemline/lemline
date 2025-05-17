// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.github.zafarkhaja.semver.Version
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.workflows.Workflows
import com.lemline.runner.cli.LemlineMixin
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.messaging.Message
import com.lemline.runner.messaging.WORKFLOW_OUT
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
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
    lateinit var mixin: LemlineMixin

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var selector: InteractiveWorkflowSelector

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

    override fun run() {
        if (name.isNullOrBlank()) error("Workflow name must be provided")

        // Parse input JSON if provided, or use an empty object if not provided.
        val inputJsonElement = getInput(input)

        // Retrieve the workflow definition from the repository
        val workflowDefinition = getDefinition(name!!, version)

        // Check if the workflow input is valid against the workflow definition
        checkInput(workflowDefinition.definition, inputJsonElement)

        // create the message
        val message = Message.newInstance(name!!, workflowDefinition.version, inputJsonElement)

        // Synchronously send the message to the workflow-out channel
        try {
            emitter.send(message.toJsonString()).toCompletableFuture().get()
            println("Instance ${message.states[NodePosition.root]?.workflowId} started successfully (name: ${workflowDefinition.name}, version: ${workflowDefinition.version}, input: $inputJsonElement)")
        } catch (e: Exception) {
            error("Failed to start workflow instance: ${e.message}")
        }
    }

    private fun getInput(input: String?): JsonElement = input?.let {
        try {
            // Parse the input string as JSON
            LemlineJson.json.parseToJsonElement(it)
        } catch (e: Exception) {
            error("Invalid JSON input: ${e.message}")
        }
    } ?: LemlineJson.jsonObject

    private fun checkInput(definition: String, input: JsonElement) {
        // Parse workflow definition into a Workflow object
        val workflow = try {
            Workflows.parse(definition)
        } catch (e: Exception) {
            error("Invalid workflow definition: ${e.message}")
        }

        // Validate input against schema if the workflow has an input schema
        workflow.input?.schema?.let { schema ->
            try {
                SchemaValidator.validate(input, schema)
            } catch (e: Exception) {
                error("Input validation failed against workflow schema: ${e.message}")
            }
        }
    }

    private fun getDefinition(name: String, version: String?): DefinitionModel = version?.let {
        definitionRepository.findByNameAndVersion(name, it)
            ?: error("Workflow with name '$name' and version '$it' not found")
    } ?: run {
        val workflows = definitionRepository.listByName(name)
        if (workflows.isEmpty()) this.error("No workflows found with name '$name'")

        workflows.maxWithOrNull { w1, w2 ->
            runCatching {
                Version.parse(w1.version).compareTo(Version.parse(w2.version))
            }.getOrDefault(w1.version.compareTo(w2.version))
        } ?: error("Failed to determine latest version for workflow '$name'")
    }

    internal fun error(msg: String): Nothing {
        System.err.println(msg)
        exitProcess(1)
    }
}
