package com.lemline.worker.outbox

import com.lemline.worker.outbox.bases.WaitOutboxTest
import com.lemline.worker.tests.profiles.MySQLTestProfile
import com.lemline.worker.tests.resources.MySQLTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * MySQL-specific implementation of WaitOutboxTest.
 */
@QuarkusTest
@QuarkusTestResource(MySQLTestResource::class)
@TestProfile(MySQLTestProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("mysql")
class MySQLWaitOutboxTest : WaitOutboxTest()