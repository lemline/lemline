// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.values.WorkflowNamespace
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.pgmq.connector.PgmqIncomingMetadata
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata
import jakarta.enterprise.context.ApplicationScoped
import kotlin.jvm.optionals.getOrNull
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Resolves the namespace of the broker destination an incoming message arrived on.
 *
 * A runner following several namespaces consumes their per-namespace destinations
 * (`<prefix>-<namespace>-<type>`, see [com.lemline.runner.common.namespaces.destinationFor])
 * on a single channel. The arrival namespace tells which of those destinations delivered a
 * given message — this is what scopes CloudEvent correlation: an event that arrived on
 * namespace A's destination must never wake listeners of namespace B, even when both are followed.
 *
 * Resolution is broker-specific (mirrors how [MessageMetaData] branches on [MessagingType]):
 * - KAFKA: the record topic is the per-namespace destination.
 * - RABBITMQ: the envelope exchange when it follows the per-namespace convention; otherwise the
 *   routing key (internal publications go to the channel exchange with routing key = namespace).
 * - PGMQ: the source queue carried by [PgmqIncomingMetadata].
 * - IN_MEMORY: the [MetaData.namespace] field when present (tests); otherwise the runner's single
 *   followed namespace.
 *
 * Resolution failures throw [IllegalStateException]: a loud failure is always preferable to
 * cross-namespace matching.
 */
@ApplicationScoped
class ArrivalNamespaceResolver(
    private val config: LemlineConfiguration,
    private val namespaceScope: NamespaceScope,
) {
    private val messagingType: MessagingType by lazy { MessagingType.fromConfigValue(config.messaging().type()) }

    private val prefix: String by lazy { config.messaging().prefix() }

    /** Resolves the namespace of the destination this [message] arrived on. */
    fun resolve(message: Message<*>, type: DestinationType): WorkflowNamespace = when (messagingType) {
        MessagingType.KAFKA -> message.kafkaArrivalNamespace(type)
        MessagingType.RABBITMQ -> message.rabbitArrivalNamespace(type)
        MessagingType.PGMQ -> message.pgmqArrivalNamespace(type)
        MessagingType.IN_MEMORY -> message.inMemoryArrivalNamespace()
    }

    private fun Message<*>.kafkaArrivalNamespace(type: DestinationType): WorkflowNamespace {
        val md = getMetadata(IncomingKafkaRecordMetadata::class.java).getOrNull()
            ?: error("Cannot resolve the arrival namespace: Kafka incoming metadata is missing")
        return namespaceFromDestination(md.topic, type)
    }

    private fun Message<*>.rabbitArrivalNamespace(type: DestinationType): WorkflowNamespace {
        val md = getMetadata(IncomingRabbitMQMetadata::class.java).getOrNull()
            ?: error("Cannot resolve the arrival namespace: RabbitMQ incoming metadata is missing")
        // Per-namespace exchange (<prefix>-<namespace>-<type>) takes precedence.
        namespaceFromDestinationOrNull(md.exchange, type)?.let { return it }
        // Internal publications go to the channel exchange with routing key = namespace.
        val routingKey: String? = md.routingKey
        check(!routingKey.isNullOrBlank()) {
            "Cannot resolve the arrival namespace: exchange '${md.exchange}' does not follow the " +
                "'$prefix-<namespace>-${type.suffix}' convention and the routing key is blank"
        }
        return WorkflowNamespace(routingKey)
    }

    private fun Message<*>.pgmqArrivalNamespace(type: DestinationType): WorkflowNamespace {
        val md = getMetadata(PgmqIncomingMetadata::class.java).getOrNull()
            ?: error("Cannot resolve the arrival namespace: PGMQ incoming metadata is missing")
        return namespaceFromDestination(md.queue, type)
    }

    private fun Message<*>.inMemoryArrivalNamespace(): WorkflowNamespace {
        getMetadata(MetaData::class.java).getOrNull()?.namespace?.let { return WorkflowNamespace(it) }
        val followed = namespaceScope.followed
        check(followed.size == 1) {
            "Cannot resolve the arrival namespace: the in-memory message carries no namespace metadata " +
                "and this runner follows ${followed.size} namespaces ($followed) — expected exactly one"
        }
        return WorkflowNamespace(followed.single())
    }

    private fun namespaceFromDestination(destination: String, type: DestinationType): WorkflowNamespace =
        namespaceFromDestinationOrNull(destination, type) ?: throw IllegalStateException(
            "Cannot resolve the arrival namespace from destination '$destination': " +
                "expected the '$prefix-<namespace>-${type.suffix}' convention"
        )

    private fun namespaceFromDestinationOrNull(destination: String, type: DestinationType): WorkflowNamespace? {
        val head = "$prefix-"
        val tail = "-${type.suffix}"
        if (!destination.startsWith(head) || !destination.endsWith(tail)) return null
        if (destination.length <= head.length + tail.length) return null
        return WorkflowNamespace(destination.substring(head.length, destination.length - tail.length))
    }
}
