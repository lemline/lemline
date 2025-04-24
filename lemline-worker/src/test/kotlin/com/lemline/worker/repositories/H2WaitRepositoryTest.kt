// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.repositories.bases.AbstractWaitRepositoryTest
import com.lemline.worker.tests.profiles.H2Profile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * Runs the AbstractWaitRepositoryTest suite against an H2 database.
 */
@QuarkusTest
@TestProfile(H2Profile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class H2WaitRepositoryTest : AbstractWaitRepositoryTest()
