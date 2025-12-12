// SPDX-License-Identifier: BUSL-1.1
plugins {
    // Apply the shared build logic from a convention plugin.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)
}

/**
 * lemline-testing: End-to-end testing framework for Lemline workflows.
 *
 * This module provides:
 * - TestWorkflowExecutor: Spawns native runner, manages Testcontainers lifecycle
 * - CloudEventCapture: Subscribes to broker, captures CloudEvents for verification
 * - CloudEventDelivery: Publishes events to trigger listen tasks
 * - WorkflowStateHooks: Await utilities for deterministic test synchronization
 *
 * Architecture: Native binary orchestration - tests spawn runner as external process,
 * interact via CLI and CloudEvents through the message broker. NO dependency on
 * lemline-runner (runner is spawned as external process).
 */

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    // Internal modules - NO lemline-runner dependency (spawned as external process)
    implementation(project(":lemline-core"))  // For workflow definition parsing
    implementation(project(":lemline-common"))

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)

    // Testcontainers (infrastructure management)
    implementation(platform(libs.testcontainers.bom))
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:kafka")
    implementation("org.testcontainers:rabbitmq")
    implementation("org.testcontainers:postgresql")
    implementation("org.testcontainers:mysql")

    // Kafka/RabbitMQ clients (for CloudEvent capture/delivery)
    implementation("org.apache.kafka:kafka-clients:3.9.0")
    implementation("com.rabbitmq:amqp-client:5.25.0")

    // CloudEvents SDK (for capture/delivery)
    implementation(libs.cloudevents.core)
    implementation("io.cloudevents:cloudevents-kafka:4.0.1")
    implementation("io.cloudevents:cloudevents-json-jackson:4.0.1")

    // Kotest (test library, but exposed as API for test consumers)
    api(enforcedPlatform(libs.kotest.bom))
    api("io.kotest:kotest-runner-junit5")
    api("io.kotest:kotest-assertions-core")
    api("io.kotest:kotest-framework-api")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
}
