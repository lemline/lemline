// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventData
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.core.testcases.impl.WorkflowTestResult
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

@ApplicationScoped
internal class AnalyticsWorkflowResultAwaiter(
    private val analyticsEventSource: WorkflowAnalyticsEventSource,
) {
    private val logger = logger()
    private val json = Json { encodeDefaults = true }

    suspend fun awaitWorkflowResult(
        workflowId: WorkflowId,
        timeout: Duration = 5.seconds
    ): WorkflowTestResult {
        val timeoutMillis = timeout.inWholeMilliseconds
        val deadline = System.currentTimeMillis() + timeoutMillis
        var cursor = 0L
        val capturedEvents = mutableListOf<CloudEvent>()

        while (System.currentTimeMillis() <= deadline) {
            val rows = analyticsEventSource.listByWorkflowIdAfter(
                workflowId = workflowId,
                afterSequenceExclusive = cursor,
                limit = QUERY_BATCH_SIZE
            )

            if (rows.isNotEmpty()) {
                cursor = rows.last().cursor
                rows.forEach { row ->
                    val event = runCatching { CloudEventService.deserialize(row.cloudEventJson) }
                        .onFailure { ex ->
                            logger.warn(ex) {
                                "Failed to deserialize analytics event row for workflow $workflowId (cursor=${row.cursor})"
                            }
                        }
                        .getOrNull() ?: return@forEach

                    capturedEvents.add(event)
                    toWorkflowResult(event, workflowId)?.let { return it }
                }
            }

            if (timeoutMillis == 0L) break
            delay(POLL_INTERVAL_MS)
        }

        throw TimeoutException(
            "Workflow $workflowId did not complete within $timeout. " +
                "Captured events: ${summary(capturedEvents)}"
        )
    }

    suspend fun findWorkflowResult(workflowId: WorkflowId): WorkflowTestResult? {
        val rows = analyticsEventSource.listByWorkflowIdAfter(
            workflowId = workflowId,
            afterSequenceExclusive = 0L,
            limit = QUERY_BATCH_SIZE
        )

        return rows.asSequence()
            .mapNotNull { row ->
                runCatching { CloudEventService.deserialize(row.cloudEventJson) }
                    .onFailure { ex ->
                        logger.warn(ex) {
                            "Failed to deserialize analytics event row for workflow $workflowId (cursor=${row.cursor})"
                        }
                    }
                    .getOrNull()
            }
            .mapNotNull { event -> toWorkflowResult(event, workflowId) }
            .firstOrNull()
    }

    suspend fun summary(workflowId: WorkflowId): String {
        val rows = analyticsEventSource.listByWorkflowIdAfter(
            workflowId = workflowId,
            afterSequenceExclusive = 0L,
            limit = QUERY_BATCH_SIZE
        )

        val events = rows.mapNotNull { row ->
            runCatching { CloudEventService.deserialize(row.cloudEventJson) }
                .onFailure { ex ->
                    logger.warn(ex) {
                        "Failed to deserialize analytics event row for workflow $workflowId (cursor=${row.cursor})"
                    }
                }
                .getOrNull()
        }

        return summary(events)
    }

    private fun toWorkflowResult(event: CloudEvent, workflowId: WorkflowId): WorkflowTestResult? {
        return when (event.type) {
            LifecycleEventType.WORKFLOW_COMPLETED.type -> {
                val eventData = event.data?.toBytes()?.let { bytes ->
                    json.decodeFromString<LifecycleEventData>(String(bytes, Charsets.UTF_8))
                }
                val data = eventData as? LifecycleEventData.WorkflowCompletedData
                WorkflowTestResult.Success(
                    output = data?.output ?: error("WorkflowCompletedData missing output for workflow $workflowId")
                )
            }

            LifecycleEventType.WORKFLOW_FAULTED.type -> {
                val jsonStr = event.data?.toBytes()?.let { String(it, Charsets.UTF_8) }
                val eventData = try {
                    jsonStr?.let { json.decodeFromString<LifecycleEventData>(it) }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to deserialize workflow.faulted event data: $jsonStr" }
                    null
                }
                val data = eventData as? LifecycleEventData.WorkflowFaultedData
                val errorMsg = data?.error?.let { err ->
                    listOfNotNull(err.type, err.title).joinToString(": ").ifEmpty { "Unknown error" }
                } ?: "Unknown error (data=$eventData, json=$jsonStr)"
                WorkflowTestResult.Failure(
                    error = errorMsg,
                    exception = null
                )
            }

            else -> null
        }
    }

    private fun summary(events: List<CloudEvent>): String {
        return events.joinToString("\n") { event ->
            val workflowId = event.getExtension("lemlineworkflowid")
            "${event.type} [workflowId=$workflowId, id=${event.id}]"
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 20L
        private const val QUERY_BATCH_SIZE = 256
    }
}
