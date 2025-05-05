import io.quarkus.gradle.tasks.QuarkusBuild

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)

    kotlin("plugin.allopen") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
    id("io.quarkus") version "3.22.1"
}

group = "com.lemline.runner"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

// ────────────────────────────────────────────────────────────────────────────
// 2) Exclude unwanted HTTP clients
// ────────────────────────────────────────────────────────────────────────────
configurations.configureEach {
    exclude("software.amazon.awssdk", "apache-client")
    exclude("software.amazon.awssdk", "netty-nio-client")
}

// ────────────────────────────────────────────────────────────────────────────
// 3) Dependencies
// ────────────────────────────────────────────────────────────────────────────
dependencies {
    // Our modules
    implementation(project(":lemline-common"))
    implementation(project(":lemline-core"))

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)

    // Quarkus core & extensions
    implementation(enforcedPlatform("io.quarkus:quarkus-bom:3.22.1"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-picocli")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-jdbc-mysql")

    // Messaging
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-messaging-rabbitmq")
    implementation("io.smallrye.reactive:smallrye-reactive-messaging-in-memory:4.27.0")

    // Serverless Workflow SDK
    implementation("io.serverlessworkflow:serverlessworkflow-api:7.0.0.Final")
    implementation("io.serverlessworkflow:serverlessworkflow-impl-core:7.0.0.Final")

    // Utilities
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")
    implementation("com.github.zafarkhaja:java-semver:0.10.2")

    // ─────────────────────────────────────────────────────────────────────────
    // Libraries below are needed for Native Compilation - DO NOT TOUCh except you know what you are doing
    // ─────────────────────────────────────────────────────────────────────────

    // JTA + JMS
    implementation("io.quarkus:quarkus-narayana-jta") {
        exclude("org.jboss.narayana.jta", "jms")
    }
    implementation("jakarta.jms:jakarta.jms-api:3.1.0")

    // Quarkus helpers
    implementation("org.jboss:jboss-vfs:3.2.15.Final")
    implementation("org.osgi:org.osgi.framework:1.10.0")

    // AWS
    implementation(platform("io.quarkus.platform:quarkus-amazon-services-bom:3.22.1"))  // BOM
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-common")               // aws-core, signer
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-s3")                   // S3 client & S3Object
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-crt")                  // aws-crt & checksum support

    // ─────────────────────────────────────────────────────────────────────────
    // 4) Testing
    // ─────────────────────────────────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform("io.kotest:kotest-bom:5.8.1"))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-jdbc-h2")
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-framework-api")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.6"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:rabbitmq")
}

// ────────────────────────────────────────────────────────────────────────────
// 5) Kotlin & JPA boilerplate
// ────────────────────────────────────────────────────────────────────────────
allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.inject.Singleton")
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    maxHeapSize = "2g"
    jvmArgs("-Xms512m")
}

// this is a hack to enable native compilation (bug in quarkus-amazon-crt)
tasks.named<QuarkusBuild>("quarkusBuild") {
    doFirst {
        file("$buildDir/lemline-runner-$version-native-image-source-jar/libaws-crt-jni.dylib").setWritable(true)
    }
}
