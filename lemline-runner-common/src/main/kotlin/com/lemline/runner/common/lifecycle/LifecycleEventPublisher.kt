// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

/**
 * Publishes one serialized lifecycle CloudEvent to the lifecycle events channel and reports
 * the outcome. Unlike the fire-and-forget emitter used by workers, [publish] throws when the
 * broker does not acknowledge the message, so the creation event outbox can retry it instead of
 * treating a failed publication as delivered.
 *
 * Implementations are provided by the main runner module.
 */
interface LifecycleEventPublisher {

    /** Whether lifecycle events are produced at all. When false, nothing is enqueued. */
    val enabled: Boolean

    /** Publishes [payload] (a CloudEvents JSON document) and waits for its acknowledgment. */
    suspend fun publish(payload: String)

    companion object {
        /** No lifecycle producer: nothing is enqueued or published. */
        val DISABLED: LifecycleEventPublisher = object : LifecycleEventPublisher {
            override val enabled = false
            override suspend fun publish(payload: String) = error("Lifecycle events are disabled")
        }
    }
}
