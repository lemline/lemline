plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.allopen") version "2.1.10"
    kotlin("plugin.jpa") version "2.1.10"
    kotlin("plugin.serialization") version "1.9.22"
    id("io.quarkus") version "3.21.0"
}

repositories {
    mavenCentral()
}

// Define the Netty version based on Quarkus BOM (usually managed automatically, but explicit for clarity)
// Let's rely on the Quarkus BOM primarily.

dependencies {
    // Enforce Quarkus platform versions
    implementation(enforcedPlatform("io.quarkus:quarkus-bom:3.21.0"))
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Quarkus
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-flyway") // DB migration
    implementation("io.quarkus:quarkus-rest-jackson") // Rest Server with Json serialization
    implementation("io.quarkus:quarkus-messaging")
    implementation("io.quarkus:quarkus-messaging-kafka") // Kafka Messaging
    implementation("io.quarkus:quarkus-messaging-rabbitmq") // RabbitMQ Messaging
    implementation("io.quarkus:quarkus-mutiny") // Reactive programming
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin") // ORM With Hibernate / Panache
    implementation("io.quarkus:quarkus-scheduler")  // Scheduler
    implementation("io.quarkus:quarkus-jdbc-postgresql") // Postgres Database driver

    // Serverless Workflow SDK
    implementation("io.serverlessworkflow:serverlessworkflow-api:7.0.0.Final")
    implementation("io.serverlessworkflow:serverlessworkflow-impl-core:7.0.0.Final")

    // Add Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.2")

    // UUID Creator
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform("io.kotest:kotest-bom:5.8.1"))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-framework-api")
    testImplementation("io.mockk:mockk:1.13.9")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers-bom:1.20.6")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:rabbitmq")
}

group = "com.lemline"
version = "1.0.0-SNAPSHOT"

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
