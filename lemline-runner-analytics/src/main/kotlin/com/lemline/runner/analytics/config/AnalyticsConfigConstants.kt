// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics.config

/**
 * Constants for analytics configuration.
 * These duplicate the property name strings from LemlineConfiguration/LemlineConfigConstants
 * to avoid a dependency on lemline-runner.
 */
object AnalyticsConfigConstants {
    const val ANALYTICS_CONSUMER_ENABLED = "lemline.analytics.consumer.enabled"
    const val ANALYTICS_CONSUMER_CONCURRENCY = "lemline.analytics.consumer.concurrency"
    const val LIFECYCLEEVENTS_IN_CHANNEL = "lifecycleevents-in"

    const val ANALYTICS_CONSUMER_ENABLED_DEFAULT = "false"
    const val ANALYTICS_CONSUMER_CONCURRENCY_DEFAULT = "64"
    const val ANALYTICS_POSTGRES_SCHEMA_DEFAULT = "public"
    const val ANALYTICS_POSTGRES_TABLE_DEFAULT = "lemline_lifecycle_events"
    const val ANALYTICS_POSTGRES_DATABASE_DEFAULT = "lemline_analytics"
    const val ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT = "true"
    const val ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT = "false"
}
