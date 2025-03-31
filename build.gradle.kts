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

dependencies {
    implementation(enforcedPlatform("io.quarkus:quarkus-bom:3.21.0"))

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Quarkus
    implementation("io.quarkus:quarkus-kotlin")
    // Flyway (DB migration)
    implementation("io.quarkus:quarkus-flyway")
    // Rest Server with Json serialization
    implementation("io.quarkus:quarkus-rest-jackson")
    // Messaging with Kafka
    implementation("io.quarkus:quarkus-messaging-kafka")
    // Reactive programming
    implementation("io.quarkus:quarkus-mutiny")
    // ORM With Hibernate / Panache
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    // Scheduler
    implementation("io.quarkus:quarkus-scheduler")
    // Postgres Database driver
    implementation("io.quarkus:quarkus-jdbc-postgresql")

    // Serverless Workflow SDK
    implementation("io.serverlessworkflow:serverlessworkflow-api:7.0.0.Final")
    implementation("io.serverlessworkflow:serverlessworkflow-impl-core:7.0.0.Final")

    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation(enforcedPlatform("io.kotest:kotest-bom:5.8.1"))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-framework-api")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:5.10.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("io.quarkus:quarkus-junit5-mockito")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
    testImplementation("org.testcontainers:kafka:1.19.7")

    // Add Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.2")

    // UUID Creator
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    // Kafka client for tests
    testImplementation("org.apache.kafka:kafka-clients:3.6.1")
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
    jvmArgs = listOf("-XX:+EnableDynamicAgentLoading")
}

//tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
//    kotlinOptions {
//        jvmTarget = "17"
//        freeCompilerArgs = listOf("-Xjsr305=strict")
//    }
//}