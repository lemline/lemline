plugins {
    // Apply the shared build logic from a convention plugin.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Internal modules
    implementation(project(":lemline-common"))
    implementation(project(":lemline-core"))
    implementation(project(":lemline-runner-common"))
    implementation(project(":lemline-runner-listeners"))  // for CloudEventService

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)

    // Quarkus core & extensions
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-jdbc-postgresql")

    // Reactive Messaging (for Message, Channel types used by subscriber/handler)
    implementation(libs.smallrye.reactive.messaging.api)
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-messaging-rabbitmq")

    // CloudEvents SDK
    implementation(libs.cloudevents.core)
    implementation(libs.cloudevents.json.jackson)

    // Micrometer
    implementation("io.quarkus:quarkus-micrometer")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)
}
