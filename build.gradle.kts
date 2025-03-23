plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.allopen") version "2.1.10"
    kotlin("plugin.jpa") version "2.1.10"
    id("io.quarkus") version "3.8.1"
}

repositories {
    mavenCentral()
}

val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("io.quarkus:quarkus-bom:$quarkusPlatformVersion"))

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Quarkus
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-resteasy-reactive-jackson")
    implementation("io.quarkus:quarkus-smallrye-reactive-messaging-kafka")
    implementation("io.quarkus:quarkus-mutiny")
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("io.quarkus:quarkus-jdbc-postgresql")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")

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