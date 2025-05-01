package com.lemline.runner.cli

import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped
import java.io.File
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

enum class WorkflowAction {
    LIST,
    SHOW,
    CREATE,
    UPDATE,
    DELETE,
    VALIDATE
}

@Unremovable
@ApplicationScoped
@Command(name = "workflow", description = ["Manage workflows"])
class WorkflowCommand : Runnable {
    @Parameters(index = "0", description = ["Action to perform (LIST, SHOW, CREATE, UPDATE, DELETE, VALIDATE)"])
    var action: String? = null

    @Option(names = ["-f", "--file"], description = ["Workflow file"])
    var file: File? = null

    @Option(names = ["-a", "--all"], description = ["Show all"])
    var all: Boolean? = null

    override fun run() {
        val workflowAction = try {
            WorkflowAction.valueOf(action?.uppercase() ?: "")
        } catch (e: IllegalArgumentException) {
            println("Invalid workflow action. Valid actions: ${WorkflowAction.entries.joinToString()}")
            return
        }
        println("Workflow action: $workflowAction")
    }
} 
