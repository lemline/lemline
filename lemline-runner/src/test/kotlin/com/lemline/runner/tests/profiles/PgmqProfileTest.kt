// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.shared.LemlineConfiguration
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for the PGMQ (PostgreSQL Message Queue) profile.
 *
 * Verifies that the profile correctly configures:
 * - PostgreSQL database for workflow state (shared with PGMQ messaging)
 * - PGMQ messaging type for message broker
 */
@RequiresDocker
@TestProfile(PgmqProfile::class)
@QuarkusTest
class PgmqProfileTest {

    @Inject
    lateinit var lemlineConfig: LemlineConfiguration

    @Test
    fun `check the PgmqProfile profile`() {
        // Check the default values of the configuration
        // PostgreSQL is used for both database and PGMQ messaging
        assertEquals(DatabaseType.POSTGRESQL.configValue, lemlineConfig.database().type())
        assertEquals(MessagingType.PGMQ.configValue, lemlineConfig.messaging().type())
    }

    @Test
    fun `verify PGMQ configuration is present`() {
        // Verify that PGMQ configuration is available
        val pgmqConfig = lemlineConfig.messaging().pgmq()
        assert(pgmqConfig.isPresent) { "PGMQ configuration should be present" }
    }
}
