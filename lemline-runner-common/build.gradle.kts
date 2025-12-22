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
    implementation(project(":lemline-core"))

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)

    // Quarkus core (for CDI annotations, scheduler)
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-scheduler")

    // SmallRye Reactive Messaging API (for Message type)
    implementation("org.eclipse.microprofile.reactive.messaging:microprofile-reactive-messaging-api:3.0")

    // Serverless Workflow SDK (for InstanceMessage state types)
    implementation(libs.serverlessworkflow.api)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)

    // Test Fixtures - Database utilities for manual DI testing
    testFixturesImplementation(libs.bundles.kotlinxEcosystem)
    testFixturesImplementation(project(":lemline-common"))
    testFixturesImplementation(project(":lemline-core"))
    testFixturesImplementation(project(":lemline-runner-common"))

    // H2 Database for in-memory testing
    testFixturesImplementation("com.h2database:h2:2.3.232")

    // HikariCP for connection pooling
    testFixturesImplementation("com.zaxxer:HikariCP:6.0.0")

    // Flyway for migrations
    testFixturesImplementation("org.flywaydb:flyway-core:11.4.0")
    testFixturesImplementation("org.flywaydb:flyway-database-postgresql:11.4.0")
    testFixturesImplementation("org.flywaydb:flyway-mysql:11.4.0")

    // Test Fixtures - Testing framework dependencies for base test classes
    testFixturesImplementation(enforcedPlatform(libs.kotest.bom))
    testFixturesImplementation("io.kotest:kotest-assertions-core")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testFixturesImplementation(libs.mockk)

    // Testcontainers for PostgreSQL and MySQL testing
    testFixturesImplementation(enforcedPlatform(libs.testcontainers.bom))
    testFixturesImplementation("org.testcontainers:testcontainers")
    testFixturesImplementation("org.testcontainers:postgresql")
    testFixturesImplementation("org.testcontainers:mysql")

    // JDBC drivers for PostgreSQL and MySQL
    testFixturesImplementation("org.postgresql:postgresql:42.7.2")
    testFixturesImplementation("com.mysql:mysql-connector-j:8.4.0")
}
