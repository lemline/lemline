plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)

    kotlin("plugin.allopen") version "2.1.20"
    kotlin("plugin.jpa") version "2.1.20"
    id("io.quarkus") version "3.21.2"
}

dependencies {
    implementation(project(":lemline-common"))
    implementation(project(":lemline-core"))

    // Apply the kotlinx bundle of dependencies from the version catalog (`gradle/libs.versions.toml`).
    implementation(libs.bundles.kotlinxEcosystem)

    // Enforce Quarkus platform versions
    implementation(enforcedPlatform("io.quarkus:quarkus-bom:3.21.2"))

    // Quarkus
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-flyway")             // DB migration
    implementation("io.quarkus:quarkus-rest-jackson")       // Rest Server with Json serialization
    implementation("io.quarkus:quarkus-messaging")
    implementation("io.quarkus:quarkus-messaging-kafka")    // Kafka Messaging
    implementation("io.quarkus:quarkus-messaging-rabbitmq") // RabbitMQ Messaging
    implementation("io.quarkus:quarkus-mutiny")             // Reactive programming
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin") // ORM With Hibernate / Panache
    implementation("io.quarkus:quarkus-scheduler")          // Scheduler
    implementation("io.quarkus:quarkus-jdbc-postgresql")    // Postgres Database driver
    implementation("io.quarkus:quarkus-jdbc-mysql")         // MySQL Database driver
    implementation("io.quarkus:quarkus-arc")

    implementation("io.quarkus:quarkus-hibernate-reactive")
    implementation("io.quarkus:quarkus-reactive-mysql-client")
    implementation("io.quarkus:quarkus-reactive-pg-client")
    implementation("io.smallrye.reactive:mutiny-kotlin-coroutines")


    // Serverless Workflow SDK
    implementation("io.serverlessworkflow:serverlessworkflow-api:7.0.0.Final")
    implementation("io.serverlessworkflow:serverlessworkflow-impl-core:7.0.0.Final")

    // UUID Creator
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    // Testing
    testImplementation(kotlin("test"))

    testImplementation(enforcedPlatform("io.kotest:kotest-bom:5.8.1"))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-jdbc-h2")
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-framework-api")
    testImplementation("io.mockk:mockk:1.13.9")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers-bom:1.20.6")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:rabbitmq")
}

group = "com.lemline.worker"
version = "0.0.1-SNAPSHOT"

allOpen {
    // Allows Hibernate to work with Kotlin classes by marking classes annotated with @Entity as open for extension.
    annotation("jakarta.persistence.Entity")
    // Marks classes annotated with @MappedSuperclass as open for extension, enabling inheritance in JPA entities.
    annotation("jakarta.persistence.MappedSuperclass")
    // Marks classes annotated with @Embeddable as open for extension, allowing embeddable types in JPA.
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
