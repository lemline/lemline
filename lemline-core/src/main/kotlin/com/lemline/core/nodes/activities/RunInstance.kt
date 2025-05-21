// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities

import com.lemline.core.activities.runs.ShellRun
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunTask
import kotlinx.coroutines.runBlocking // Required for calling suspend functions from non-suspend context if needed, or for execute method if it becomes suspend.

class RunInstance(override val node: Node<RunTask>, override val parent: NodeInstance<*>) :
    NodeInstance<RunTask>(node, parent) {

    // TODO: This executeTask method is a placeholder and needs to be properly integrated
    // with the workflow execution logic.
    // It might need to be a suspend function depending on the surrounding architecture.
    fun executeTask(): Any? { // Return type may need to be more specific, e.g., ProcessResult or a generic Map
        val task = node.definition // Assuming node.definition gives access to the RunTask
        
        // Determine the type of RunTask and delegate accordingly
        // This example focuses on ShellRun
        if (task.run?.shell != null) {
            val shellProps = task.run.shell
            val command = shellProps.command ?: throw IllegalArgumentException("Shell command must be specified")
            // Assuming arguments in RunTask's shell are List<String> and need conversion to Map<String, String>
            // For simplicity, this example assumes arguments are already in a suitable format or null
            // Adjust this part based on the actual structure of shellProps.arguments
             val argumentsMap: Map<String, String>? = shellProps.arguments?.let { argsList ->
                // This is a simplistic conversion. You might need a more robust way 
                // to handle arguments depending on how they are structured in RunTask.
                // Example: if argsList is ["--verbose", "true", "-f", "myfile.txt"]
                // it needs to be converted to mapOf("--verbose" to "true", "-f" to "myfile.txt")
                // For now, let's assume it's a map or can be directly used/adapted.
                // If arguments are defined as a single string, it needs parsing.
                // If it's a list of key-value pairs or a map, it's more straightforward.
                // Given the DSL reference: "arguments | `map` | `no` | A list of the arguments of the shell command to run"
                // it seems it should be a map. If io.serverlessworkflow.api.types.RunTask.Shell uses a List or String,
                // appropriate conversion is needed here.
                // For this example, if it's already a Map<String, String>, great. Otherwise, this needs to be implemented.
                if (argsList is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    argsList as? Map<String, String>
                } else {
                    // Handle other types e.g. List<String> to Map<String, String>
                    // This is a placeholder for actual conversion logic if needed
                    // For example, if it's a list like ["arg1", "val1", "arg2", "val2"]
                    // it could be: it.chunked(2).associate { it[0] to it[1] }
                    null // Or throw an error if the format is not as expected
                }
            }

            val environmentMap: Map<String, String>? = shellProps.environment?.let { envMap ->
                 if (envMap is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    envMap as? Map<String, String>
                } else {
                    null
                }
            }

            val shellRun = ShellRun(
                command = command,
                arguments = argumentsMap,
                environment = environmentMap
            )
            // The actual execution might need to be asynchronous
            // and integrate with the workflow engine's execution model.
            // If ShellRun.execute() is a suspend function, this method should also be suspend
            // or runBlocking (though runBlocking is often discouraged in reactive/async code).
            return shellRun.execute()
        }
        // TODO: Add handling for other run types like container, script, workflow

        // Fallback or error if no known run type is specified
        throw NotImplementedError("This run type is not supported yet.")
    }
}
