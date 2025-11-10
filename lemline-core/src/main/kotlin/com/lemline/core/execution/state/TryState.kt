// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.lemline.core.execution.state

import com.lemline.core.errors.WorkflowError
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * State for TryTask execution.
 *
 * TryTask is unique among flow tasks in that it stores `transformedInput` to provide
 * consistent input for retries and catch blocks. This is the only flow task that stores input.
 *
 * Created with
 * - attemptIndex = -1
 * - runningCatch = false
 *
 * Has Entered the Try Block:
 * - attemptIndex = ? (> 0)
 * - runningCatch = false
 *
 * When Orchestrator has caught an error and attributed it to this node:
 *  If retry,
 * - attemptIndex = ? (> 0)
 * - runningCatch = false
 * If catch
 * - attemptIndex = ? (> 0)
 * - runningCatch = false
 * Note: we do not
 *
 * @property startedAt When the task started
 * @property transformedInput Input after input.from transformation (stored for retries/catch, nullable to save memory)
 * @property attemptIndex Current retry attempt (0-based, 0 = first attempt, 1 = first retry, etc.)
 * @property runningCatch Whether currently executing in catch block
 * @property lastError Last caught error object (for context)
 */
@Serializable
data class TryState(
    override val startedAt: Instant,
    val transformedInput: JsonElement,
    val attemptIndex: Int,
    val runningCatch: Boolean,
    val lastError: WorkflowError? = null,
    @Transient
    val errorAs: String = "error"
) : NodeState() {

    fun newAttemptState(error: WorkflowError? = null): TryState = TryState(
        startedAt = startedAt,
        transformedInput = transformedInput,
        attemptIndex = attemptIndex + 1,
        runningCatch = false,
        lastError = lastError,
        errorAs = errorAs
    )

    fun toCatchState(error: WorkflowError): TryState = TryState(
        startedAt = startedAt,
        transformedInput = transformedInput,
        attemptIndex = attemptIndex,
        runningCatch = true, // <= flag used to avoid catching an exception from the catch block
        lastError = error,
        errorAs = errorAs
    )

    /**
     * If it exists, add the error to the scope
     */
    override val scope: Scope by lazy {
        buildJsonObject {
            lastError?.let { put(errorAs, Json.encodeToJsonElement(it)) }
        }
    }


}
