// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.ANALYTICS_BACKEND_POSTGRESQL
import com.lemline.runner.gateway.config.GatewayRuntimeConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

@ApplicationScoped
class WorkflowAnalyticsEventSourceSelector(
    private val config: GatewayRuntimeConfig,
) : WorkflowAnalyticsEventSource {

    @Inject
    lateinit var postgresqlEventSource: Instance<PostgresqlWorkflowAnalyticsEventSource>

    private val backend get() = AnalyticsBackend.parse(config.analyticsType)

    override suspend fun listByWorkflowIdAfter(
        workflowId: WorkflowId,
        afterSequenceExclusive: Long,
        limit: Int,
    ): List<WorkflowAnalyticsEventRow> =
        delegate().listByWorkflowIdAfter(workflowId, afterSequenceExclusive, limit)

    override suspend fun validate(): Unit = delegate().validate()

    private fun delegate(): WorkflowAnalyticsEventSource = when (backend) {
        AnalyticsBackend.POSTGRESQL -> {
            if (postgresqlEventSource.isResolvable) {
                postgresqlEventSource.get()
            } else {
                throw IllegalStateException(
                    "Analytics type '$ANALYTICS_BACKEND_POSTGRESQL' requires datasource 'analytics'. " +
                        "Configure lemline.analytics.postgresql.*"
                )
            }
        }

        AnalyticsBackend.CLICKHOUSE -> {
            throw IllegalStateException(
                "Analytics type 'clickhouse' is not implemented yet in lemline-gateway. " +
                    "Use lemline.analytics.type=postgresql."
            )
        }
    }
}
