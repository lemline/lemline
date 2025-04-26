// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.repositories.bases.RetryRepositoryTest
import com.lemline.worker.tests.profiles.MySQLProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * Runs the RetryRepositoryTest suite against a MySQL database.
 */
@QuarkusTest
@TestProfile(MySQLProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class MySQLRetryRepositoryTest : RetryRepositoryTest()
