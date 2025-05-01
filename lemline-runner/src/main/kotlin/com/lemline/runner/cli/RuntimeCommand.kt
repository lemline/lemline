package com.lemline.runner.cli

import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

enum class RuntimeAction {
    START,
    STOP,
    STATUS,
    LOGS,
    RESTART
}

@Unremovable
@Command(name = "runtime", description = ["Manage runtime"])
class RuntimeCommand : Runnable {
    @Parameters(index = "0", description = ["Action to perform (START, STOP, STATUS, LOGS, RESTART)"])
    var action: String? = null

    @Option(names = ["-d", "--daemon"], description = ["Run as daemon"])
    var daemon: Boolean = false

    override fun run() {
        val runtimeAction = try {
            RuntimeAction.valueOf(action?.uppercase() ?: "")
        } catch (e: IllegalArgumentException) {
            println("Invalid runtime action. Valid actions: ${RuntimeAction.entries.joinToString()}")
            return
        }
        println("Runtime action: $runtimeAction")
    }
} 
