// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.github.zafarkhaja.semver.Version
import com.lemline.core.json.LemlineJson
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.workflows.Workflows
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.messaging.WORKFLOW_IN
import com.lemline.runner.messaging.WorkflowMessage
import com.lemline.runner.repositories.DefinitionRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Unremovable
@Command(
    name = "start",
    description = ["Get specific workflow definitions, interactively if needed."],
    mixinStandardHelpOptions = true,
)
class InstanceStartCommand : Runnable {

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var selector: InteractiveWorkflowSelector

    @Inject
    @Channel(WORKFLOW_IN)
    lateinit var workflowEmitter: Emitter<String>

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
        val inputJsonElement: JsonElement = input?.let {
            try {
                LemlineJson.encodeToElement(it)
            } catch (e: Exception) {
                error("Invalid JSON input: ${e.message}")
            }
        } ?: LemlineJson.jsonObject

        val workflowDefinition = version?.let {
            definitionRepository.findByNameAndVersion(name!!, it)
                ?: error("Workflow with name '$name' and version '$it' not found")
        } ?: run {
            val workflows = definitionRepository.listByName(name!!)
            if (workflows.isEmpty()) error("No workflows found with name '$name'")

            workflows.maxWithOrNull { w1, w2 ->
                runCatching {
                    Version.parse(w1.version).compareTo(Version.parse(w2.version))
                }.getOrDefault(w1.version.compareTo(w2.version))
            } ?: error("Failed to determine latest version for workflow '$name'")
        }

        // Parse workflow definition into a Workflow object
        val workflow = Workflows.parse(workflowDefinition.definition)

        // Validate input against schema if the workflow has an input schema
        workflow.input?.schema?.let { schema ->
            try {
                SchemaValidator.validate(inputJsonElement, schema)
            } catch (e: Exception) {
                error("Input validation failed against workflow schema: ${e.message}")
            }
        }

        val message = WorkflowMessage.newInstance(name!!, workflowDefinition.version, inputJsonElement)

        // Send the message to the workflow-in channel
        workflowEmitter.send(message.toJsonString())

        println("Started workflow: ${workflowDefinition.name} version ${workflowDefinition.version}")
    }

    private fun error(msg: String): Nothing {
        throw CommandLine.ParameterException(CommandLine(this), msg)
    }
}
