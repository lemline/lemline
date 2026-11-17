// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.core.states.WorkflowEvent.ActivityStarted

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
     * Treat event JSON trees as read-only values. Copy a tree before modifying it;
     * states and concurrent workflow branches can share the same input snapshots.
     *
     * @param event The activity to execute, containing all necessary configuration
     * @return The result of the activity execution as JsonNode
     * @throws Exception if the activity fails
     */
    suspend fun execute(event: ActivityStarted): JsonNode
}
