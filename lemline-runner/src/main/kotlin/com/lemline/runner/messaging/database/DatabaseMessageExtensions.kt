// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.instances.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Creates an InfrastructureFailure message from an exception.
 *
 * @param error The exception that caused the failure
 * @param reason A low-cardinality failure reason for metrics (see FailureReasons)
 * @param retryable true if the error is transient and should be retried (e.g., DB down, broker unavailable),
 *                  false if the error is permanent (e.g., malformed data, logic error)
 */
@ExperimentalTime
@ExperimentalSerializationApi
fun InstanceMessage.toInfrastructureFailure(
    error: Throwable,
    reason: String = getFailureReason(error),
    retryable: Boolean
): DatabaseMessage.InfrastructureFailure = DatabaseMessage.InfrastructureFailure(
    instance = this,
    errorClass = error::class.qualifiedName ?: "Unknown",
    errorMessage = error.message,
    errorStackTrace = error.stackTraceToString(),
    reason = reason,
    retryable = retryable
)

/**
 * Creates a DeserializationFailure message from an exception.
 *
 * Used when we cannot parse the incoming message into an InstanceMessage.
 * This is always a permanent failure (stored in FailureModel for manual inspection).
 *
 * @param payload The raw message payload that failed to deserialize
 * @param error The exception that occurred during deserialization
 */
@ExperimentalTime
@ExperimentalSerializationApi
fun createDeserializationFailure(
    payload: String,
    error: Throwable
): DatabaseMessage.DeserializationFailure = DatabaseMessage.DeserializationFailure(
    payload = payload,
    errorClass = error::class.qualifiedName ?: "Unknown",
    errorMessage = error.message,
    errorStackTrace = error.stackTraceToString()
)
