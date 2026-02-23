// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.common.values.WorkflowId
import com.lemline.runner.gateway.config.GatewayConfigConstants.ANALYTICS_TYPE
import com.lemline.runner.gateway.config.GatewayConfigConstants.ANALYTICS_TYPE_DEFAULT
import com.lemline.runner.gateway.config.GatewayConfigConstants.ANALYTICS_TYPE_POSTGRESQL
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class WorkflowAnalyticsEventSourceSelector(
    @ConfigProperty(name = ANALYTICS_TYPE, defaultValue = ANALYTICS_TYPE_DEFAULT)
    analyticsBackend: String,
) : WorkflowAnalyticsEventSource {

    @Inject
    lateinit var postgresqlEventSource: Instance<PostgresqlWorkflowAnalyticsEventSource>

    private val backend = AnalyticsBackend.parse(analyticsBackend)

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
                    "Analytics type '$ANALYTICS_TYPE_POSTGRESQL' requires datasource 'analytics'. " +
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
