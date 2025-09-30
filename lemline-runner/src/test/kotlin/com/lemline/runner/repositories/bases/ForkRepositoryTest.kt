// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.ForkModel
import com.lemline.runner.outbox.bases.RunStatus
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ForkRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ForkRepositoryTest : CleanerRepositoryTest<ForkModel>() {

    @Inject
    override lateinit var repository: ForkRepository

    override fun createRandomEntity(
        runStatus: RunStatus,
        runAt: Instant
    ) = ForkModel.random().copy(runStatus = runStatus, runAt = runAt)

    override fun modify(model: ForkModel) = ForkModel.random().copy(id = model.id)
}
