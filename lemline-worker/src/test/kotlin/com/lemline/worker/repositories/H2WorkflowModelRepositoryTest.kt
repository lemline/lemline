package com.lemline.worker.repositories

import com.lemline.worker.repositories.bases.AbstractWorkflowModelRepositoryTest
import com.lemline.worker.tests.resources.H2TestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Runs the AbstractWorkflowModelRepositoryTest suite against an H2 database.
 */
@QuarkusTest
@QuarkusTestResource(H2TestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("h2")
class H2WorkflowModelRepositoryTest : AbstractWorkflowModelRepositoryTest() 