package com.lemline.runner.messaging

import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata
import kotlin.jvm.optionals.getOrNull
import org.eclipse.microprofile.reactive.messaging.Message as ReactiveMessage

fun ReactiveMessage<*>.toLogString() = try {
    getMetadata(IncomingKafkaRecordMetadata::class.java).getOrNull()?.let {
        return "KafkaMessage(topic=${it.topic}, partition=${it.partition}, offset=${it.offset}, key=${it.key})"
    }

    getMetadata(IncomingRabbitMQMetadata::class.java).getOrNull()?.let {
        return "RabbitMQMessage(exchange=${it.exchange}, routingKey=${it.routingKey})"
    }

    toString()
} catch (e: Exception) {
    "Message(type=${this::class.simpleName}, payload=<error accessing payload: ${e.message}>)"
}
