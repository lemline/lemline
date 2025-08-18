// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
class LemlineConfigurationTest {

    @Inject
    lateinit var lemlineConfig: LemlineConfiguration

    @Test
    fun testConfigurationValues() {
        // Check the default values of the configuration
        assertEquals(DB_TYPE_IN_MEMORY, lemlineConfig.database().type())
        assertEquals(MSG_TYPE_IN_MEMORY, lemlineConfig.messaging().type())

        assertEquals(5, lemlineConfig.outbox().retry().outbox().maxAttempts())
        assertEquals(1000, lemlineConfig.outbox().retry().outbox().batchSize())
        assertEquals(30.seconds, lemlineConfig.outbox().retry().outbox().initialDelay().toDuration())
        assertEquals(1.hours, lemlineConfig.outbox().wait().cleanup().every().toDuration())
    }
}
