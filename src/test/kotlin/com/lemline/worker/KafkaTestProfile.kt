package com.lemline.worker

import io.quarkus.test.junit.QuarkusTestProfile

class KafkaTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "kafka-test"
}