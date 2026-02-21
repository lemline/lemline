// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import io.cloudevents.CloudEvent
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
internal class PostgresLifecycleAnalyticsSink(
    private val service: LifecycleAnalyticsService,
) : LifecycleAnalyticsSink {
    override suspend fun ingest(event: CloudEvent): IngestResult {
        return service.ingest(event)
    }
}
