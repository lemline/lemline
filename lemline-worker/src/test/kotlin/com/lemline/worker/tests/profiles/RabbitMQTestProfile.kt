package com.lemline.worker.tests.profiles

import io.quarkus.test.junit.QuarkusTestProfile

class RabbitMQTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "rabbitmq-test"
} 