// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.definitions.DefinitionConfig
import com.lemline.runner.forks.ForkFeatureConfig
import com.lemline.runner.listeners.ListenerConfig
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.parents.ParentFeatureConfig
import com.lemline.runner.retries.RetryConfig
import com.lemline.runner.schedules.ScheduleConfig
import com.lemline.runner.waits.WaitConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * CDI producer for feature configuration beans and infrastructure interfaces.
 * Converts [LemlineConfiguration] to feature-specific configuration interfaces
 * and provides [CommandEmitter] for use by feature modules.
 */
@ExperimentalTime
@ApplicationScoped
class ConfigProducer {

    @Inject
    lateinit var config: LemlineConfiguration

    @Inject
    internal lateinit var workflowCommandEmitter: WorkflowCommandEmitter

    @Produces
    @ApplicationScoped
    fun commandEmitter(): CommandEmitter = object : CommandEmitter {
        override suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7?) {
            workflowCommandEmitter.send(message, idempotentKey)
        }
    }

    @Produces
    @ApplicationScoped
    fun waitConfig(): WaitConfig = object : WaitConfig {
        private val source = config.outbox().wait()
        override val enabled: Boolean get() = source.enabled().orElse(true)
        override val outbox: OutboxConfig get() = source.outbox().toOutboxProcessingConfig()
        override val cleanup: CleanupConfig get() = source.cleanup().toOutboxCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun retryConfig(): RetryConfig = object : RetryConfig {
        private val source = config.outbox().retry()
        override val enabled: Boolean get() = source.enabled().orElse(true)
        override val outbox: OutboxConfig get() = source.outbox().toOutboxProcessingConfig()
        override val cleanup: CleanupConfig get() = source.cleanup().toOutboxCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun scheduleConfig(): ScheduleConfig = object : ScheduleConfig {
        private val source = config.outbox().schedule()
        override val enabled: Boolean get() = source.enabled().orElse(true)
        override val outbox: OutboxConfig get() = source.outbox().toOutboxProcessingConfig()
        override val cleanup: CleanupConfig get() = source.cleanup().toOutboxCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun listenerConfig(): ListenerConfig = object : ListenerConfig {
        private val source = config.outbox().listener().orElse(null)
        override val enabled: Boolean get() = source?.enabled()?.orElse(true) ?: true
        override val outbox: OutboxConfig? get() = source?.outbox()?.toOutboxProcessingConfig()
        override val cleanup: CleanupConfig get() = source?.cleanup()?.toOutboxCleanupConfig() ?: defaultCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun parentConfig(): ParentFeatureConfig = object : ParentFeatureConfig {
        private val source = config.outbox().parent()
        override val enabled: Boolean get() = source.enabled().orElse(true)
        override val cleanup: CleanupConfig get() = source.cleanup().toOutboxCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun forkConfig(): ForkFeatureConfig = object : ForkFeatureConfig {
        private val source = config.outbox().fork()
        override val enabled: Boolean get() = source.enabled().orElse(true)
        override val cleanup: CleanupConfig get() = source.cleanup().toOutboxCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun definitionConfig(): DefinitionConfig = object : DefinitionConfig {
        override val enabled: Boolean
            get() = config.messaging().cloudevents().getOrNull()?.consumer()?.enabled() ?: false
        override val syncEvery: Duration
            get() = config.definitions().getOrNull()?.cache()?.getOrNull()?.syncEvery ?: 10.seconds
    }

    private fun LemlineConfiguration.OutboxProcessingConfig.toOutboxProcessingConfig(): OutboxConfig =
        object : OutboxConfig {
            override val every: Duration get() = this@toOutboxProcessingConfig.every
            override val batchSize: Int get() = this@toOutboxProcessingConfig.batchSize
            override val initialDelay: Duration get() = this@toOutboxProcessingConfig.initialDelay
            override val maxAttempts: Int get() = this@toOutboxProcessingConfig.maxAttempts
        }

    private fun LemlineConfiguration.OutboxCleanupConfig.toOutboxCleanupConfig(): CleanupConfig =
        object : CleanupConfig {
            override val every: Duration get() = this@toOutboxCleanupConfig.every
            override val after: Duration get() = this@toOutboxCleanupConfig.after
            override val batchSize: Int get() = this@toOutboxCleanupConfig.batchSize
        }

    private fun defaultCleanupConfig(): CleanupConfig = object : CleanupConfig {
        override val every: Duration = Duration.parse("1h")
        override val after: Duration = Duration.parse("7d")
        override val batchSize: Int = 1000
    }
}
