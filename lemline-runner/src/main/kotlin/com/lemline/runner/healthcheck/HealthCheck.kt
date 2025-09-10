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
            HealthCheckResponse.down("orders-readiness")
        else
            HealthCheckResponse.up("orders-readiness")
}

@Liveness
object FatalAckLiveness : HealthCheck {
    val livenessDownOnFatal = AtomicBoolean(false)

    override fun call(): HealthCheckResponse =
        if (livenessDownOnFatal.get())
            HealthCheckResponse.down("orders-liveness")
        else
            HealthCheckResponse.up("orders-liveness")
}
