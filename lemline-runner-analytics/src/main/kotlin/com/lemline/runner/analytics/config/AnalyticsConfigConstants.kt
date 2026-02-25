// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics.config

/**
 * Default values for analytics configuration.
 * Property keys are centralized in lemline-runner-common/config/LemlineConfigKeys.kt.
 */
object AnalyticsConfigConstants {
    const val LIFECYCLE_EVENTS_CONSUMER_ENABLED_DEFAULT = "false"
    const val LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY_DEFAULT = "64"
    const val ANALYTICS_POSTGRES_SCHEMA_DEFAULT = "lemline"
    const val ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT = "true"
}
