package com.lemline.runner.cli

import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped
import java.io.File
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

enum class InstanceAction {
    LIST,
    SHOW,
    CREATE,
    TERMINATE,
    LOGS
}

@Unremovable
@ApplicationScoped
@Command(name = "instance", description = ["Manage workflow instances"])
class InstanceCommand : Runnable {
    @Parameters(index = "0", description = ["Action to perform (LIST, SHOW, CREATE, TERMINATE, LOGS)"])
    var action: String? = null

    @Option(names = ["-w", "--workflow"], description = ["Workflow ID"])
    var workflowId: String? = null

    @Option(names = ["-i", "--input"], description = ["Input file"])
    var inputFile: File? = null

    override fun run() {
        val instanceAction = try {
            InstanceAction.valueOf(action?.uppercase() ?: "")
        } catch (e: IllegalArgumentException) {
            println("Invalid instance action. Valid actions: ${InstanceAction.entries.joinToString()}")
            return
        }
        println("Instance action: $instanceAction")
    }
} 
