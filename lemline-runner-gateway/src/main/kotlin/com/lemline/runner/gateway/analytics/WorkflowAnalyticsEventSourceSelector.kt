// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.ANALYTICS_BACKEND_POSTGRESQL
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.analyticsTypeResolved
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

@ApplicationScoped
class WorkflowAnalyticsEventSourceSelector(
    private val config: LemlineConfiguration,
) : WorkflowAnalyticsEventSource {

    @Inject
    lateinit var postgresqlEventSource: Instance<PostgresqlWorkflowAnalyticsEventSource>

    private val backend get() = AnalyticsBackend.parse(config.analyticsTypeResolved)

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
                        "Configure $LEMLINE_ANALYTICS_POSTGRES.*"
                )
            }
        }

        AnalyticsBackend.CLICKHOUSE -> {
            throw IllegalStateException(
                "Analytics type 'clickhouse' is not implemented yet in lemline-gateway. " +
                    "Use $LEMLINE_ANALYTICS_TYPE=$ANALYTICS_BACKEND_POSTGRESQL."
            )
        }
    }
}
