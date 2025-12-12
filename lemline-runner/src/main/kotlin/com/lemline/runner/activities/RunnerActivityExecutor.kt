// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.common.logger.logger
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.runner.messaging.cloudevents.CloudEventsEmitter
import io.quarkus.arc.Arc
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Runner-specific ActivityExecutor that integrates with the messaging infrastructure.
 *
 * Wraps [DefaultActivityExecutor] and provides:
 * - CloudEvents emission via [CloudEventsEmitter] (if enabled)
 * - HTTP calls, script execution, shell commands via default implementation
 *
 * The CloudEventsEmitter is conditionally available based on configuration.
 * If not enabled, emit tasks will log a warning but not fail the workflow.
 *
 * Note: This class is NOT a CDI bean. It's instantiated by [ActivityExecutorProducer]
 * which decides which executor to use based on test mode configuration.
 */
@ExperimentalTime
class RunnerActivityExecutor : ActivityExecutor {

    private val logger = logger()

    /**
     * Lazy-loaded CloudEvents emitter. May be null if CloudEvents producer is not enabled.
     * Uses Arc to look up the bean since it's conditionally created.
     */
    private val cloudEventsEmitter: CloudEventsEmitter? by lazy {
        Arc.container().instance(CloudEventsEmitter::class.java).orElse(null)
    }

    /**
     * The delegate executor that performs the actual I/O operations.
     * Initialized lazily to capture the CloudEventsEmitter at first use.
     */
    private val delegate: DefaultActivityExecutor by lazy {
        DefaultActivityExecutor(
            emitCloudEvent = { cloudEvent ->
                val emitter = cloudEventsEmitter
                if (emitter != null) {
                    emitter.send(cloudEvent)
                } else {
                    logger.warn { "CloudEvents emitter not available - emit task will not publish event. Enable cloudevents.producer to emit CloudEvents." }
                }
            }
        )
    }

    /**
     * Execute the given activity by delegating to [DefaultActivityExecutor].
     *
     * @param event The activity event containing configuration for execution
     * @return The result of the activity execution
     * @throws Exception if the activity fails
     */
    override suspend fun execute(event: ActivityStarted): JsonElement {
        return delegate.execute(event)
    }
}
