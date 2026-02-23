// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.runner.gateway.config.GatewayConfigConstants.ANALYTICS_TYPE_CLICKHOUSE
import com.lemline.runner.gateway.config.GatewayConfigConstants.ANALYTICS_TYPE_POSTGRESQL
import java.util.Locale

enum class AnalyticsBackend(val value: String) {
    POSTGRESQL(ANALYTICS_TYPE_POSTGRESQL),
    CLICKHOUSE(ANALYTICS_TYPE_CLICKHOUSE),
    ;

    companion object {
        fun parse(raw: String): AnalyticsBackend {
            val normalized = raw.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.value == normalized }
                ?: throw IllegalStateException(
                    "Unsupported analytics type '$raw'. Supported values: " +
                        "'$ANALYTICS_TYPE_POSTGRESQL', '$ANALYTICS_TYPE_CLICKHOUSE'."
                )
        }
    }
}
