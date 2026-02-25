// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.ANALYTICS_TYPE_H2
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE

internal object AnalyticsH2TestProfileSupport {
    fun overrides(databaseName: String): Map<String, String> = mapOf(
        LEMLINE_ANALYTICS_TYPE to ANALYTICS_TYPE_H2,
        "quarkus.datasource.analytics.db-kind" to "h2",
        "quarkus.datasource.analytics.active" to "true",
        "quarkus.datasource.analytics.username" to "sa",
        "quarkus.datasource.analytics.password" to "sa",
        "quarkus.datasource.analytics.jdbc.url" to
            "jdbc:h2:mem:$databaseName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "quarkus.flyway.analytics.locations" to "classpath:db/migration/analytics/h2/"
    )
}
