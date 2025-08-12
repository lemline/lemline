// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.utils.IdGenerator
import com.lemline.runner.outbox.OutBoxStatus
import java.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

const val RUN_WORKFLOW_TABLE = "lemline_run_workflows"

@Serializable
data class RunWorkflowModel(
    override val id: String = IdGenerator.generateTimeBasedId(),

    override val message: String,

    override var status: OutBoxStatus = OutBoxStatus.PENDING,

    override var delayedUntil: @Contextual Instant? = null,

    override var attemptCount: Int = 0,

    override var lastError: String? = null,
) : OutboxModel()
