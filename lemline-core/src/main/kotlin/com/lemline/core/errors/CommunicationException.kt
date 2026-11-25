// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

/**
 * Thrown by an activity when an exchange with an external service fails:
 * the service answered with a failing status, or could not be reached at all.
 *
 * It is reported to the workflow as a `communication` error whose status is [status],
 * so that `catch.errors.with` can filter on the type and on the status.
 *
 * @property status The status answered by the service, or the default communication
 * status when the service gave no answer.
 */
class CommunicationException(
    message: String,
    val status: Int = WorkflowErrorType.COMMUNICATION.defaultStatus,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
