package com.lemline.runtime

import io.quarkus.test.junit.QuarkusTestProfile

class KafkaTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "kafka-test"
    
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            "quarkus.test.profile.props" to "kafka-test.properties"
        )
    }
} 