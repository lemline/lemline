// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.repositories.bases.WaitRepositoryTest
import com.lemline.worker.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * Runs the WaitRepositoryTest suite against an H2 database.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class H2WaitRepositoryTest : WaitRepositoryTest()
