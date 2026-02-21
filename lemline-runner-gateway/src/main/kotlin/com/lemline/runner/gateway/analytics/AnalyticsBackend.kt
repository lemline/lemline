// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.runner.gateway.config.GatewayConfigConstants.ANALYTICS_BACKEND_CLICKHOUSE
import com.lemline.runner.gateway.config.GatewayConfigConstants.ANALYTICS_BACKEND_POSTGRESQL
import java.util.Locale

enum class AnalyticsBackend(val value: String) {
    POSTGRESQL(ANALYTICS_BACKEND_POSTGRESQL),
    CLICKHOUSE(ANALYTICS_BACKEND_CLICKHOUSE),
    ;

    companion object {
        fun parse(raw: String): AnalyticsBackend {
            val normalized = raw.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.value == normalized }
                ?: throw IllegalStateException(
                    "Unsupported analytics backend '$raw'. Supported values: " +
                        "'$ANALYTICS_BACKEND_POSTGRESQL', '$ANALYTICS_BACKEND_CLICKHOUSE'."
                )
        }
    }
}
