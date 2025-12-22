// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.with

import com.lemline.common.values.WorkflowId
import java.sql.Connection
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Interface for repositories that support workflow instance operations.
 * Implemented by repositories with InstanceRepository composition.
 */
@ExperimentalSerializationApi
@ExperimentalTime
interface WithInstanceRepository<T> {
    suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection? = null): T?
}
