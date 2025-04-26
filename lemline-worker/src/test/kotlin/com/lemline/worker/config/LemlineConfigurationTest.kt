// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.config

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
class LemlineConfigurationTest {

    @Inject
    lateinit var lemlineConfig: LemlineConfiguration

    @Test
    fun testConfigurationValues() {
        // Check the default values of the configuration
        assertEquals(5, lemlineConfig.retry().outbox().maxAttempts())
        assertEquals(100, lemlineConfig.retry().outbox().batchSize())
        assertEquals(Duration.ofSeconds(10), lemlineConfig.retry().outbox().initialDelay())
        assertEquals(Duration.ofHours(1), lemlineConfig.wait().cleanup().every())
    }
}
