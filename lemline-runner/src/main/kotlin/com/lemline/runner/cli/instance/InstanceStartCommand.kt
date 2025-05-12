// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instance

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.zafarkhaja.semver.Version
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.repositories.DefinitionRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
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
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var selector: InteractiveWorkflowSelector

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
        if (name.isNullOrBlank()) {
            throw CommandLine.ParameterException(CommandLine(this), "Workflow name must be provided")
        }

        input?.let {
            try {
                objectMapper.readTree(it)
            } catch (e: JsonProcessingException) {
                throw CommandLine.ParameterException(CommandLine(this), "Invalid JSON input: ${e.message}")
            }
        }

        val workflow = version?.let {
            definitionRepository.findByNameAndVersion(name!!, it)
                ?: throw CommandLine.ParameterException(
                    CommandLine(this),
                    "Workflow with name '$name' and version '$it' not found"
                )
        } ?: run {
            val workflows = definitionRepository.listByName(name!!)
            if (workflows.isEmpty()) {
                throw CommandLine.ParameterException(CommandLine(this), "No workflows found with name '$name'")
            }
            workflows.maxWithOrNull { w1, w2 ->
                runCatching {
                    Version.parse(w1.version).compareTo(Version.parse(w2.version))
                }.getOrDefault(w1.version.compareTo(w2.version))
            } ?: throw CommandLine.ParameterException(
                CommandLine(this),
                "Failed to determine latest version for workflow '$name'"
            )
        }

        println("Selected workflow: ${workflow.name} version ${workflow.version}")
    }
}
