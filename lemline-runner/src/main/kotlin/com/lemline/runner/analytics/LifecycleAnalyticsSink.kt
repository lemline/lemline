// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import io.cloudevents.CloudEvent

internal interface LifecycleAnalyticsSink {
    suspend fun ingest(event: CloudEvent): IngestResult
}

internal enum class IngestResult {
    INSERTED,
    DUPLICATE
}
