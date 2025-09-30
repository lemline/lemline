package com.lemline.runner.outbox.bases

import com.lemline.common.debug
import com.lemline.common.logger.Logger
import kotlin.time.Duration

/**
 * Represents a task or operation that can be scheduled for execution.
 */
interface Schedulable {
    suspend fun run()
    val isEnabled: Boolean
    val every: Duration
    val gracePeriod: Duration
    val logger: Logger
}

internal fun Logger.logBatches(success: Int, total: Int, batchNumber: Int, action: String) {
    val failed = total - success
    when (total) {
        0 -> logger.debug { "No message found to process" }
        else -> when {
            failed == 0 -> logger.debug { "All ${total.messages()} $action successfully (over ${batchNumber.batches()})" }
            else -> logger.debug { "${success.messages()} $action successfully and ${failed.messages()} failed (over ${batchNumber.batches()})" }
        }
    }
}

internal fun Int.messages(): String = this.toString() + " message" + if (this <= 1) "" else "s"
internal fun Int.batches(): String = this.toString() + " batch" + if (this <= 1) "" else "es"
