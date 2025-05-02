// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.WaitRepository
import jakarta.inject.Inject

/**
 * Abstract base class for wait repository tests.
 */
internal abstract class WaitRepositoryTest : OutboxRepositoryTest<WaitModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override fun createWithMessage(message: String) = WaitModel(message = message)

    override fun copyModel(model: WaitModel, message: String) = model.copy(message = message)
}
