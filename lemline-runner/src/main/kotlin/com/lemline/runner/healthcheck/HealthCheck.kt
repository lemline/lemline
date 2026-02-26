// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.healthcheck

import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Liveness
import org.eclipse.microprofile.health.Readiness


@Readiness
object RetryReadiness : HealthCheck {
    // Health flags (optional): expose via Quarkus SmallRye Health
    val readinessDownDuringRetries = AtomicBoolean(false)

    override fun call(): HealthCheckResponse =
        if (readinessDownDuringRetries.get())
            HealthCheckResponse.down("worker-readiness")
        else
            HealthCheckResponse.up("worker-readiness")
}

@Liveness
object FatalAckLiveness : HealthCheck {
    val livenessDownOnFailure = AtomicBoolean(false)

    override fun call(): HealthCheckResponse =
        if (livenessDownOnFailure.get())
            HealthCheckResponse.down("worker-liveness")
        else
            HealthCheckResponse.up("worker-liveness")
}
