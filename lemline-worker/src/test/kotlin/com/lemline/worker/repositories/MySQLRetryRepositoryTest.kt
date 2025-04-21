// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.repositories.bases.AbstractRetryRepositoryTest
import com.lemline.worker.tests.resources.MySQLTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * MySQL-specific implementation of RetryRepositoryTest.
 */
@QuarkusTest
@QuarkusTestResource(MySQLTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("mysql")
class MySQLRetryRepositoryTest : AbstractRetryRepositoryTest()
