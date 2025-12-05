// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.h2

import com.lemline.runner.repositories.bases.ListenerEventRepositoryTest
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.TestInstance

/**
 * Runs the ListenerEventRepositoryTest suite against an H2 in-memory database.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExperimentalTime
@ExperimentalSerializationApi
internal class H2ListenerEventRepositoryTest : ListenerEventRepositoryTest()
