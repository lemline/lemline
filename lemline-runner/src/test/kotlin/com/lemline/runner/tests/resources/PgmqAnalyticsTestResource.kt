// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * PGMQ test resource for lifecycle analytics integration tests.
 *
 * Wraps [PgmqTestResource] and forces lifecycleevents channel queue auto-creation
 * via system properties so they take precedence over generated config defaults.
 */
class PgmqAnalyticsTestResource : QuarkusTestResourceLifecycleManager {
    private val delegate = PgmqTestResource()

    override fun start(): Map<String, String> {
        val base = delegate.start()
        val overrides = mapOf(
            "mp.messaging.incoming.lifecycleevents-in.auto-create-queue" to "true",
            "mp.messaging.outgoing.lifecycleevents-out.auto-create-queue" to "true"
        )

        overrides.forEach { (k, v) -> System.setProperty(k, v) }
        return base + overrides
    }

    override fun stop() {
        System.clearProperty("mp.messaging.incoming.lifecycleevents-in.auto-create-queue")
        System.clearProperty("mp.messaging.outgoing.lifecycleevents-out.auto-create-queue")
        delegate.stop()
    }
}
