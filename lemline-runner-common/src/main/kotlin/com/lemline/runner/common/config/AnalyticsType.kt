// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

/**
 * Supported analytics storage types for Lemline.
 */
enum class AnalyticsType(val configValue: String) {
    /** H2 analytics storage (default for tests/local runs). */
    H2(ANALYTICS_TYPE_H2),

    /** PostgreSQL analytics storage. */
    POSTGRESQL(ANALYTICS_TYPE_POSTGRESQL),
    ;

    companion object {
        fun fromConfigValue(value: String): AnalyticsType =
            entries.find { it.configValue == value }
                ?: throw IllegalArgumentException("Unknown analytics type: '$value'")
    }
}
