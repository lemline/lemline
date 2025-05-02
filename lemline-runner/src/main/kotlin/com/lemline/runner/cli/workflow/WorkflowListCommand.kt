package com.lemline.runner.cli.workflow

import com.lemline.runner.repositories.WorkflowRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand

@Unremovable
@Command(name = "list", description = ["List available workflows"])
class WorkflowListCommand : Runnable {

    @Inject
    lateinit var workflowRepository: WorkflowRepository

    @Option(names = ["--format"], description = ["Output format (text, json, yaml)"], defaultValue = "text")
    var format: String = "text"

    // Workflow Command class
    @ParentCommand
    lateinit var parent: WorkflowCommand

    override fun run() {
        // we stop after this command
        parent.parent.daemon = false

        println("Listing workflows...")
        val workflows = workflowRepository.listAll() // Assuming repository has list() method
        // TODO: Format and print based on 'format' option
        workflows.forEach { println(it) } // Placeholder output
    }
}
