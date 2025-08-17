// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.ids.IdGenerator
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

const val RUN_WORKFLOW_TABLE = "lemline_run_workflows"

@ExperimentalTime
data class RunWorkflowModel(
    override val id: String = IdGenerator.generateTimeBasedId(),

    override val workflowId: String,

    override val workflowName: String,

    override val workflowVersion: String,

    override val workflowPosition: String,

    override val workflowState: String,

    override var outBoxStatus: OutBoxStatus = OutBoxStatus.PENDING,

    override var outboxScheduledFor: Instant? = null,

    override var outboxDelayedUntil: Instant? = outboxScheduledFor,

    override var outboxAttemptCount: Int = 0,

    override var outboxLastError: String? = null,
) : OutboxModel()
