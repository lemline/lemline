// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.failures

import com.lemline.runner.common.messaging.FailureReasons as CommonFailureReasons

/**
 * Re-exports [CommonFailureReasons] for backward compatibility.
 * The canonical implementation now lives in `lemline-runner-common`.
 */
object FailureReasons {
    const val OUTBOX_FAILURE = CommonFailureReasons.OUTBOX_FAILURE
    const val DESERIALIZATION_FAILURE = CommonFailureReasons.DESERIALIZATION_FAILURE
    const val SERIALIZATION_FAILURE = CommonFailureReasons.SERIALIZATION_FAILURE
    const val DEFINITION_MISSING = CommonFailureReasons.DEFINITION_MISSING
    const val SECRETS_RETRIEVAL_FAILURE = CommonFailureReasons.SECRETS_RETRIEVAL_FAILURE
    const val WORKFLOW_EXECUTION_FAILURE = CommonFailureReasons.WORKFLOW_EXECUTION_FAILURE
    const val MESSAGE_EMISSION_FAILURE = CommonFailureReasons.MESSAGE_EMISSION_FAILURE
    const val DATABASE_FAILURE = CommonFailureReasons.DATABASE_FAILURE
    const val IO_FAILURE = CommonFailureReasons.IO_FAILURE
    const val ILLEGAL_STATE_FAILURE = CommonFailureReasons.ILLEGAL_STATE_FAILURE
    const val GENERAL_PROCESSING_FAILURE = CommonFailureReasons.GENERAL_PROCESSING_FAILURE

    fun getFailureReason(e: Throwable): String = CommonFailureReasons.getFailureReason(e)
}
