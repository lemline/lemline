// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.common.logger.logger
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import jakarta.enterprise.context.ApplicationScoped
import java.util.*

@ApplicationScoped
class LifecycleAnalyticsService(
    private val repository: LifecycleAnalyticsRepository,
) {
    private val logger = logger()

    suspend fun ingest(event: CloudEvent): IngestResult {
        return repository.insert(toRow(event))
    }

    internal fun toRow(event: CloudEvent): LifecycleAnalyticsModel {
        return LifecycleAnalyticsModel(
            eventId = event.id,
            source = event.source.toString(),
            type = event.type,
            specVersion = event.specVersion.toString(),
            subject = event.subject,
            eventTime = event.time,
            dataContentType = event.dataContentType,
            dataSchema = event.dataSchema?.toString(),
            workflowId = parseUuidExtension(event, LEMLINE_WORKFLOW_ID_EXTENSION),
            workflowNamespace = extensionAsString(event, LEMLINE_WORKFLOW_NAMESPACE_EXTENSION),
            workflowName = extensionAsString(event, LEMLINE_WORKFLOW_NAME_EXTENSION),
            workflowVersion = extensionAsString(event, LEMLINE_WORKFLOW_VERSION_EXTENSION),
            payload = CloudEventService.serialize(event)
        )
    }

    private fun extensionAsString(event: CloudEvent, extensionName: String): String? {
        return event.getExtension(extensionName)?.toString()?.takeUnless { it.isBlank() }
    }

    private fun parseUuidExtension(event: CloudEvent, extensionName: String): UUID? {
        val value = extensionAsString(event, extensionName) ?: return null

        return runCatching { UUID.fromString(value) }
            .onFailure {
                logger.warn {
                    "Ignoring malformed UUID extension '$extensionName' for lifecycle event id=${event.id}: value='$value'"
                }
            }
            .getOrNull()
    }

    companion object {
        private const val LEMLINE_WORKFLOW_ID_EXTENSION = "lemlineworkflowid"
        private const val LEMLINE_WORKFLOW_NAMESPACE_EXTENSION = "lemlineworkflownamespace"
        private const val LEMLINE_WORKFLOW_NAME_EXTENSION = "lemlineworkflowname"
        private const val LEMLINE_WORKFLOW_VERSION_EXTENSION = "lemlineworkflowversion"
    }
}
