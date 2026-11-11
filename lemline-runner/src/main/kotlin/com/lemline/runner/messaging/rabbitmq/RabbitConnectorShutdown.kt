// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.rabbitmq

import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.LemlineConfiguration
import io.quarkus.runtime.ShutdownEvent
import io.smallrye.reactive.messaging.rabbitmq.RabbitMQConnector
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Any
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.spi.ObserverMethod
import jakarta.inject.Inject

@ApplicationScoped
class RabbitConnectorShutdown(private val config: LemlineConfiguration) {
    @Inject
    @Any
    lateinit var connectors: Instance<RabbitMQConnector>

    // Subscribers and scheduled tasks drain at the default priority. Close the connector afterwards, while
    // Vert.x is still available, rather than waiting for application-scope destruction.
    // RabbitMQConnector.terminate clears its clients, so its later CDI hook is harmless.
    fun onShutdown(@Observes @Priority(ObserverMethod.DEFAULT_PRIORITY + 100) event: ShutdownEvent) {
        if (config.messaging().type() != MessagingType.RABBITMQ.configValue) return
        connectors.forEach { it.terminate(null) }
    }
}
