// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.*
import com.lemline.runner.tests.resources.PostgresTestResource
import com.lemline.runner.tests.resources.SqliteMessagingTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

class SqliteProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = sqliteWorkflowChannels + mapOf(
        // Observe the runner's emissions on the workflow namespace "test". Only the runner
        // consumes the input on "test-in", just as in the PGMQ consumer suite.
        LEMLINE_NAMESPACES to "test-in",
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
        "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-sqlite",
        "mp.messaging.incoming.lifecycleevents-in.queue" to "lemline-lifecycle-events",
        "mp.messaging.incoming.lifecycleevents-in.path" to "${'$'}{$LEMLINE_MESSAGING_SQLITE_PATH}",
    )

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(TestNamespaceScope::class.java)

    override fun testResources() = sqlitePostgresResources()
}

internal val sqliteWorkflowChannels = mapOf(
    LEMLINE_MESSAGING_TYPE to MessagingType.SQLITE.configValue,
    LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
    LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
    LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
    LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",
    LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "true",
    LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "true",
)

internal fun sqlitePostgresResources() =
    listOf(TestResourceEntry(SqliteMessagingTestResource::class.java), TestResourceEntry(PostgresTestResource::class.java))
