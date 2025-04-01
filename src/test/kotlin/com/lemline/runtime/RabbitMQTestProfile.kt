package com.lemline.runtime

import io.quarkus.test.junit.QuarkusTestProfile

class RabbitMQTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "rabbitmq-test"
} 