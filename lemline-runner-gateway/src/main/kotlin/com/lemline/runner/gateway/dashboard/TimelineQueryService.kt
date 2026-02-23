// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.WorkflowParser
import com.lemline.runner.gateway.config.GatewayRuntimeConfig
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ApplicationScoped
class TimelineQueryService(
    config: GatewayRuntimeConfig,
) {

    @Inject
    @DataSource("analytics")
    lateinit var analyticsDataSource: Instance<AgroalDataSource>

    @Inject
    lateinit var definitionQueryService: DefinitionQueryService

    private val analyticsQualifiedTable = DashboardSqlSupport.qualifiedTable(config.analyticsSchema, config.analyticsTable)

    suspend fun getInstanceTimeline(
        workflowId: UUID,
        includeDefinition: Boolean,
    ): InstanceTimelineRow? = withContext(Dispatchers.IO) {
        val rows = listTimelineRows(workflowId)
        if (rows.isEmpty()) return@withContext null

        val namespace = rows.firstNotNullOfOrNull { it.namespace?.takeIf(String::isNotBlank) } ?: ""
        val name = rows.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) } ?: ""
        val version = rows.firstNotNullOfOrNull { it.version?.takeIf(String::isNotBlank) } ?: ""

        val definition = if (includeDefinition && namespace.isNotBlank() && name.isNotBlank()) {
            definitionQueryService.getDefinition(namespace, name, version.takeIf { it.isNotBlank() })?.definition
        } else {
            null
        }

        val taskNameByPointer = definition
            ?.let(::buildTaskNameMap)
            ?: emptyMap()

        val events = rows.map { row ->
            TimelineEventRow(
                sequence = row.sequence,
                eventType = row.eventType,
                eventTime = row.eventTime,
                category = row.category,
                taskPointer = row.taskPointer,
                taskName = resolveTaskName(taskNameByPointer, row.taskPointer),
                payloadJson = row.payloadJson,
            )
        }

        InstanceTimelineRow(
            workflowId = workflowId,
            namespace = namespace,
            name = name,
            version = version,
            status = deriveStatus(rows),
            events = events,
            definition = definition,
        )
    }

    private suspend fun listTimelineRows(workflowId: UUID): List<RawTimelineRow> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT
              id,
              type,
              event_time,
              payload::text AS payload_json,
              lemline_workflow_namespace,
              lemline_workflow_name,
              lemline_workflow_version,
              CASE
                WHEN type LIKE 'io.serverlessworkflow.workflow.%' THEN 'WORKFLOW'
                ELSE 'TASK'
              END AS category,
              payload->'data'->>'task' AS task_pointer
            FROM $analyticsQualifiedTable
            WHERE lemline_workflow_id = ?
            ORDER BY event_time ASC NULLS FIRST, id ASC
        """.trimIndent()

        requireAnalyticsDataSource().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RawTimelineRow(
                                    sequence = rs.getLong("id"),
                                    eventType = rs.getString("type"),
                                    eventTime = rs.getInstantOrNull("event_time"),
                                    payloadJson = rs.getString("payload_json") ?: "{}",
                                    category = rs.getString("category") ?: "TASK",
                                    taskPointer = rs.getString("task_pointer"),
                                    namespace = rs.getString("lemline_workflow_namespace"),
                                    name = rs.getString("lemline_workflow_name"),
                                    version = rs.getString("lemline_workflow_version"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun deriveStatus(rows: List<RawTimelineRow>): WorkflowRuntimeStatus {
        var hasCreated = false
        var hasStarted = false
        var completedSequence: Long? = null
        var faultedSequence: Long? = null

        rows.forEach { row ->
            when (row.eventType) {
                LifecycleEventType.WORKFLOW_CREATED.type -> hasCreated = true
                LifecycleEventType.WORKFLOW_STARTED.type -> hasStarted = true
                LifecycleEventType.WORKFLOW_COMPLETED.type -> completedSequence = row.sequence
                LifecycleEventType.WORKFLOW_FAULTED.type -> faultedSequence = row.sequence
            }
        }

        if (completedSequence != null && faultedSequence != null) {
            return if (completedSequence!! >= faultedSequence!!) {
                WorkflowRuntimeStatus.COMPLETED
            } else {
                WorkflowRuntimeStatus.FAULTED
            }
        }

        return when {
            completedSequence != null -> WorkflowRuntimeStatus.COMPLETED
            faultedSequence != null -> WorkflowRuntimeStatus.FAULTED
            hasStarted -> WorkflowRuntimeStatus.RUNNING
            hasCreated -> WorkflowRuntimeStatus.PENDING
            else -> WorkflowRuntimeStatus.UNKNOWN
        }
    }

    private fun buildTaskNameMap(definition: String): Map<String, String> {
        val workflow = runCatching { WorkflowCache.parseYaml(definition) }.getOrNull() ?: return emptyMap()
        val (_, nodesMap) = WorkflowParser.parse(workflow)

        return nodesMap.entries
            .map { (position, node) -> position.toString() to node.name }
            .toMap()
    }

    private fun resolveTaskName(taskNameByPointer: Map<String, String>, taskPointer: String?): String? {
        val pointer = taskPointer?.trim()?.takeIf { it.startsWith("/") && it.length > 1 } ?: return null
        taskNameByPointer[pointer]?.let { return it }

        val tokens = pointer.removePrefix("/")
            .split('/')
            .filter { it.isNotBlank() }
            .map(::decodePointerToken)

        if (tokens.isEmpty()) return null

        // Native NodePosition pointer shape in Lemline: /do/{index}/{taskName}
        if (tokens.size >= 2 && tokens[tokens.lastIndex - 1].toIntOrNull() != null) {
            return tokens.last()
        }

        // Spec-style pointer shape: /do/steps/{index}/{taskType}
        val stepsIndex = tokens.indexOf("steps")
        if (stepsIndex >= 0 && stepsIndex + 1 < tokens.size) {
            val stepNumber = tokens[stepsIndex + 1].toIntOrNull()
            if (stepNumber != null) {
                val prefix = "/do/$stepNumber/"
                taskNameByPointer.entries.firstOrNull { it.key.startsWith(prefix) }?.value?.let { return it }
            }
        }

        return tokens.lastOrNull()
    }

    private fun decodePointerToken(raw: String): String =
        raw.replace("~1", "/").replace("~0", "~")

    private fun requireAnalyticsDataSource(): AgroalDataSource {
        if (analyticsDataSource.isResolvable) return analyticsDataSource.get()
        throw IllegalStateException(
            "Analytics datasource 'analytics' is not available. Configure lemline.analytics.postgresql.*"
        )
    }

    private data class RawTimelineRow(
        val sequence: Long,
        val eventType: String,
        val eventTime: java.time.Instant?,
        val payloadJson: String,
        val category: String,
        val taskPointer: String?,
        val namespace: String?,
        val name: String?,
        val version: String?,
    )
}
