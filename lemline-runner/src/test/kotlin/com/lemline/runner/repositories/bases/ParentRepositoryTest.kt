// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.ParentWaitingModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ParentWaitingRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Test

/**
 * Abstract base class for ParentWaitingRepository tests.
 * Tests basic CRUD operations for parent workflow waiting state.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ParentWaitingRepositoryTest {

    @Inject
    lateinit var repository: ParentWaitingRepository

    @Test
    fun `should insert and find parent waiting model`() = runTest {
        val model = ParentWaitingModel.random()

        repository.insert(model)

        val found = repository.findById(model.id)
        found shouldNotBe null
        found!!.id shouldBe model.id
        found.instanceMessage.workflowInfo shouldBe model.instanceMessage.workflowInfo
    }

    @Test
    fun `should delete parent waiting model`() = runTest {
        val model = ParentWaitingModel.random()

        repository.insert(model)
        val found = repository.findById(model.id)
        found shouldNotBe null

        repository.delete(model)

        val notFound = repository.findById(model.id)
        notFound shouldBe null
    }
}
