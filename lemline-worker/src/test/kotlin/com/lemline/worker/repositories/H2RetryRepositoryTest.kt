// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.repositories.bases.AbstractRetryRepositoryTest
import com.lemline.worker.tests.profiles.H2KafkaProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * Runs the AbstractRetryRepositoryTest suite against an H2 database.
 */
@QuarkusTest
@TestProfile(H2KafkaProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H2RetryRepositoryTest : AbstractRetryRepositoryTest()
