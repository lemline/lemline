// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.ParentModel
import com.lemline.runner.outbox.bases.RunStatus
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ParentRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ParentRepositoryTest : CleanerRepositoryTest<ParentModel>() {

    @Inject
    override lateinit var repository: ParentRepository

    override fun createRandomEntity(
        runStatus: RunStatus,
        runAt: Instant
    ) = ParentModel.random().copy(runStatus = runStatus, runAt = runAt)

    override fun modify(model: ParentModel) = ParentModel.random().copy(id = model.id)
}
