// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_PGMQ
import com.lemline.runner.config.LemlineConfiguration
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
        assertEquals(DB_TYPE_POSTGRESQL, lemlineConfig.database().type())
        assertEquals(MSG_TYPE_PGMQ, lemlineConfig.messaging().type())
    }

    @Test
    fun `verify PGMQ configuration is present`() {
        // Verify that PGMQ configuration is available
        val pgmqConfig = lemlineConfig.messaging().pgmq()
        assert(pgmqConfig.isPresent) { "PGMQ configuration should be present" }
    }
}
