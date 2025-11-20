// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.h2

import com.lemline.runner.repositories.bases.ForkWaitingRepositoryTest
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Runs the ForkWaitingRepositoryTest suite against an H2 database.
 *
 * Note: Some concurrent tests are disabled for H2 due to different
 * transaction isolation behavior compared to PostgreSQL/MySQL.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExperimentalTime
@ExperimentalSerializationApi
internal class H2ForkRepositoryTest : ForkWaitingRepositoryTest() {

    @Test
    @Disabled("H2 has different transaction isolation behavior - test is flaky")
    override fun `should handle concurrent branch updates without race conditions`() = runTest {
        // Disabled for H2
    }
}
