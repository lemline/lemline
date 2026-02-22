// SPDX-License-Identifier: BUSL-1.1
@file:Suppress("unused")

package com.lemline.runner.messaging

import com.lemline.runner.common.messaging.MessageHandler
import com.lemline.runner.healthcheck.FatalAckLiveness.livenessDownOnFailure
import com.lemline.runner.healthcheck.RetryReadiness.readinessDownDuringRetries

/**
 * Mixin interface that wires health check probes to the retry callbacks
 * defined in [com.lemline.runner.common.messaging.MessageHandler].
 *
 * Implement this interface alongside [MessageHandler] in lemline-runner
 * handlers to get automatic liveness/readiness integration.
 */
interface HealthCheckAwareHandler<T> : MessageHandler<T> {
    override fun onRetryStarted() {
        readinessDownDuringRetries.set(true)
    }

    override fun onRetrySucceeded() {
        readinessDownDuringRetries.set(false)
    }

    override fun onRetryExhausted() {
        livenessDownOnFailure.set(true)
    }
}
