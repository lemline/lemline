package com.lemline.worker.tests.profiles

import io.quarkus.test.junit.QuarkusTestProfile

class KafkaTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "kafka-test"
}