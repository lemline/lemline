// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities

import com.lemline.core.instances.WaitInstance
import kotlinx.coroutines.delay

/**
 * An ActivityRunner responsible for handling the `RunTask`.
 * It inspects the task configuration and dispatches to the appropriate
 * execution logic for running a shell command or a script.
 */
class WaitTaskRunner : ActivityRunner<WaitInstance> {

    override suspend fun run(instance: WaitInstance) {
        /**
         * Duration for which the workflow should wait before resuming.
         * The duration is extracted from the WaitTask and converted to a Duration using ISO-8601 duration format.
         * Examples: "PT15S" (15 seconds), "PT1H" (1 hour), "P1D" (1 day) */
        delay(instance.delay)

        // Set the rawOutput to the transformedInput
        instance.rawOutput = instance.transformedInput
    }
}
