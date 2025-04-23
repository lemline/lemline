// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.tests.resources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Test resource for an H2 in-memory database.
 * This configures Quarkus to use an H2 database for tests.
 */
class H2TestResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        // Return profile and custom property
        return mapOf(
            "quarkus.profile" to "h2",
            "lemline.database.type" to "h2",
        )
    }

    override fun stop() {
        // Nothing to do
    }
}
