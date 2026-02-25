// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES
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

    override suspend fun listByWorkflowIdAfter(
        workflowId: WorkflowId,
        afterSequenceExclusive: Long,
        limit: Int,
    ): List<WorkflowAnalyticsEventRow> =
        delegate().listByWorkflowIdAfter(workflowId, afterSequenceExclusive, limit)

    override suspend fun validate(): Unit = delegate().validate()

    private fun delegate(): WorkflowAnalyticsEventSource {
        if (postgresqlEventSource.isResolvable) {
            return postgresqlEventSource.get()
        }
        throw IllegalStateException(
            "Analytics event source is not available for analytics type '${config.analyticsTypeResolved}'. " +
                "For PostgreSQL, configure $LEMLINE_ANALYTICS_POSTGRES.*"
        )
    }
}
