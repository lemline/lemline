// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities

import com.lemline.core.activities.runs.runScript
import com.lemline.core.activities.runs.runShell
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.instances.RunInstance
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunShell

/**
 * An ActivityRunner responsible for handling the `RunTask`.
 * It inspects the task configuration and dispatches to the appropriate
 * execution logic for running a shell command or a script.
 */
class RunTaskRunner : ActivityRunner<RunInstance> {
    override suspend fun run(instance: RunInstance) {
        val runTask = instance.node.task
        // Get the specific run configuration (e.g., RunScript or RunShell)
        val run = runTask.run.get()

        // Delegate to the appropriate private helper and set the rawOutput
        instance.rawOutput = when (run) {
            is RunScript -> instance.runScript(run)
            is RunShell -> instance.runShell(run)
            else -> instance.onError(RUNTIME, "Unsupported run type: ${run.javaClass.simpleName}")
        }
    }
}
