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
import com.lemline.runner.gateway.outbox.GatewayOutboxConfig
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * CDI producer for feature configuration beans and infrastructure interfaces.
 * Converts [LemlineConfiguration] to feature-specific configuration interfaces
 * and provides [CommandEmitter] for use by feature modules.
 */
@ApplicationScoped
class ConfigProducer {

    @Inject
    lateinit var config: LemlineConfiguration

    @Inject
    internal lateinit var workflowCommandEmitter: WorkflowCommandEmitter

    @Inject
    @ConfigProperty(name = "lemline.gateway.enabled", defaultValue = "false")
    lateinit var gatewayEnabled: String

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
        private val source = config.outbox().wait().getOrNull()
        override val enabled: Boolean get() = source?.enabled() ?: true
        override val outbox: OutboxConfig get() = source?.outbox()?.toOutboxProcessingConfig() ?: defaultOutboxConfig()
        override val cleanup: CleanupConfig get() = source?.cleanup()?.toOutboxCleanupConfig() ?: defaultCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun retryConfig(): RetryConfig = object : RetryConfig {
        private val source = config.outbox().retry().getOrNull()
        override val enabled: Boolean get() = source?.enabled() ?: true
        override val outbox: OutboxConfig get() = source?.outbox()?.toOutboxProcessingConfig() ?: defaultOutboxConfig()
        override val cleanup: CleanupConfig get() = source?.cleanup()?.toOutboxCleanupConfig() ?: defaultCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun scheduleConfig(): ScheduleConfig = object : ScheduleConfig {
        private val source = config.outbox().schedule().getOrNull()
        override val enabled: Boolean get() = source?.enabled() ?: true
        override val outbox: OutboxConfig get() = source?.outbox()?.toOutboxProcessingConfig() ?: defaultOutboxConfig()
        override val cleanup: CleanupConfig get() = source?.cleanup()?.toOutboxCleanupConfig() ?: defaultCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun listenerConfig(): ListenerConfig = object : ListenerConfig {
        private val source = config.outbox().listener().getOrNull()
        override val enabled: Boolean get() = source?.enabled() ?: true
        override val outbox: OutboxConfig get() = source?.outbox()?.toOutboxProcessingConfig() ?: defaultOutboxConfig()
        override val cleanup: CleanupConfig get() = source?.cleanup()?.toOutboxCleanupConfig() ?: defaultCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun parentConfig(): ParentFeatureConfig = object : ParentFeatureConfig {
        private val source = config.outbox().parent().getOrNull()
        override val enabled: Boolean get() = source?.enabled() ?: true
        override val cleanup: CleanupConfig get() = source?.cleanup()?.toOutboxCleanupConfig() ?: defaultCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun forkConfig(): ForkFeatureConfig = object : ForkFeatureConfig {
        private val source = config.outbox().fork().getOrNull()
        override val enabled: Boolean get() = source?.enabled() ?: true
        override val cleanup: CleanupConfig get() = source?.cleanup()?.toOutboxCleanupConfig() ?: defaultCleanupConfig()
    }

    @Produces
    @ApplicationScoped
    fun gatewayOutboxConfig(): GatewayOutboxConfig = object : GatewayOutboxConfig {
        private val source = config.outbox().gateway().getOrNull()
        override val enabled: Boolean get() = gatewayEnabled == "true" && (source?.enabled() ?: true)
        override val outbox: OutboxConfig get() = source?.outbox()?.toOutboxProcessingConfig() ?: defaultGatewayOutboxConfig()
        override val cleanup: CleanupConfig get() = source?.cleanup()?.toOutboxCleanupConfig() ?: defaultCleanupConfig()
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
            override val initialJitter: Duration get() = this@toOutboxProcessingConfig.initialJitter
            override val retryDelay: Duration get() = this@toOutboxProcessingConfig.retryDelay
            override val maxAttempts: Int get() = this@toOutboxProcessingConfig.maxAttempts
        }

    private fun LemlineConfiguration.OutboxCleanupConfig.toOutboxCleanupConfig(): CleanupConfig =
        object : CleanupConfig {
            override val every: Duration get() = this@toOutboxCleanupConfig.every
            override val initialJitter: Duration get() = this@toOutboxCleanupConfig.initialJitter
            override val after: Duration get() = this@toOutboxCleanupConfig.after
            override val batchSize: Int get() = this@toOutboxCleanupConfig.batchSize
        }

    private fun defaultGatewayOutboxConfig(): OutboxConfig = object : OutboxConfig {
        override val every: Duration = 1.seconds
        override val batchSize: Int = 1000
        override val initialJitter: Duration = 3.seconds
        override val retryDelay: Duration = 30.seconds
        override val maxAttempts: Int = 5
    }

    private fun defaultCleanupConfig(): CleanupConfig = object : CleanupConfig {
        override val every: Duration = 1.hours
        override val initialJitter: Duration = 30.minutes
        override val after: Duration = 7.days
        override val batchSize: Int = 1000
    }

    private fun defaultOutboxConfig(): OutboxConfig = object : OutboxConfig {
        override val every: Duration = 10.seconds
        override val batchSize: Int = 1000
        override val initialJitter: Duration = 3.seconds
        override val retryDelay: Duration = 30.seconds
        override val maxAttempts: Int = 5
    }
}
