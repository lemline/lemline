// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities

import com.lemline.core.states.WorkflowEvent.ActivityStarted
import kotlinx.serialization.json.JsonElement

/**
 * Interface for executing workflow activities.
 *
 * Activities are tasks that perform external work (HTTP calls, script execution,
 * event emission, etc.) and return a result. The executor is responsible for
 * actually performing this work.
 *
 * This interface allows for different implementations:
 * - [DefaultActivityExecutor]: Real execution with actual I/O
 * - Mock implementations for testing
 * - Custom implementations for specific environments
 */
interface ActivityExecutor {

    /**
     * Execute the given activity and return the result.
     *
     * @param event The activity to execute, containing all necessary configuration
     * @return The result of the activity execution as JsonElement
     * @throws Exception if the activity fails
     */
    suspend fun execute(event: ActivityStarted): JsonElement
}
