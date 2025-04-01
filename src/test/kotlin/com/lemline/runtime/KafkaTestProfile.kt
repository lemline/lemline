package com.lemline.runtime

import io.quarkus.test.junit.QuarkusTestProfile

class KafkaTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "kafka-test"
}