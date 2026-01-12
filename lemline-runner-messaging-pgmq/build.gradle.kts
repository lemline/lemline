// SPDX-License-Identifier: BUSL-1.1
plugins {
    // Apply the shared build logic from a convention plugin.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)

    id("java-test-fixtures")
}

dependencies {
    // Internal modules
    implementation(project(":lemline-common"))

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.10.2")

    // Quarkus core (for CDI annotations)
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")

    // SmallRye Reactive Messaging API
    implementation(libs.smallrye.reactive.messaging.api)

    // SmallRye Reactive Messaging provider (for base connector classes)
    implementation("io.smallrye.reactive:smallrye-reactive-messaging-provider")

    // Mutiny for reactive streams
    implementation("io.smallrye.reactive:mutiny")
    implementation("io.smallrye.reactive:mutiny-kotlin")

    // Vert.x for async PostgreSQL client
    implementation("io.vertx:vertx-pg-client")
    implementation("io.vertx:vertx-sql-client")
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-pg-client")

    // PostgreSQL JDBC driver (for configuration validation)
    implementation("org.postgresql:postgresql")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)
    testImplementation(testFixtures(project(":lemline-common")))
    testImplementation(testFixtures(project(":lemline-runner-common")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")

    // Test Fixtures
    testFixturesImplementation(libs.bundles.kotlinxEcosystem)
    testFixturesImplementation(testFixtures(project(":lemline-common")))
}
