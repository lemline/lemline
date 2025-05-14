import io.quarkus.gradle.tasks.QuarkusBuild

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)

    kotlin("plugin.allopen") version libs.versions.kotlin.get()
    kotlin("plugin.jpa") version libs.versions.kotlin.get()
    alias(libs.plugins.quarkus)
}

group = "com.lemline"
version = "0.1.0-SNAPSHOT"


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
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-picocli")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-jdbc-mysql")
    implementation("io.quarkus:quarkus-hibernate-validator")

    // Messaging
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-messaging-rabbitmq")
    implementation(libs.smallrye.reactive.messaging.inmemory)

    // Serverless Workflow SDK
    implementation(libs.serverlessworkflow.api)
    implementation(libs.serverlessworkflow.impl.core)

    // Utilities
    implementation(libs.uuidCreator)
    implementation(libs.javaSemver)

    // Jackson for JSON serialization/deserialization
    implementation(libs.jackson.bom)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

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
    implementation(platform(libs.quarkus.amazon.services.bom))
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-common")
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-s3")
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-crt")

    // ─────────────────────────────────────────────────────────────────────────
    // 4) Testing
    // ─────────────────────────────────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-jdbc-h2")
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-framework-api")
    testImplementation(libs.mockk)
    testImplementation(platform(libs.testcontainers.bom))
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
        val jarLocation = "${layout.buildDirectory.get()}/lemline-runner-$version-native-image-source-jar"
        // Linux, MacOs, Windows version of the aws-crt jni library
        listOf("libaws-crt-jni.so", "libaws-crt-jni.dylib", "aws-crt-jni.dll").forEach { lib ->
            val libFile = file("$jarLocation/$lib")
            if (libFile.exists()) libFile.setWritable(true)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 6) Version Properties Generation for Picocli
// ────────────────────────────────────────────────────────────────────────────
tasks.register("generateVersionProperties") {
    group = "build"
    description = "Generates a properties file with the project version."
    val outputDir = project.layout.buildDirectory.dir("generated/lemline/resources/main")
    val versionPropsFile = outputDir.map { it.file("version.properties") }

    outputs.file(versionPropsFile) // Declare the output file

    doLast {
        val propsFile = versionPropsFile.get().asFile
        propsFile.parentFile.mkdirs()
        propsFile.writeText("version=${project.version}\n")
        println("Generated version.properties with version: ${project.version}")
    }
}

tasks.register<Exec>("codesignNativeBinary") {
    group = "build"
    description = "Codesign the native binary after build"
    commandLine("codesign", "-s", "-", "build/lemline-runner-${project.version}-runner")
    dependsOn("build") // Ensure it runs after the build task
}


// Add the generated resources to the main source set
sourceSets.main.get().resources.srcDir(
    tasks.named("generateVersionProperties").map { it.outputs.files.singleFile.parentFile })

// Ensure the task runs before resources are processed
tasks.named("processResources") {
    dependsOn("generateVersionProperties")
}

// When building a native image, we might need to ensure
// version.properties is included in the native image resources.
// Quarkus usually handles this automatically if it's in src/main/resources,
// but if we face issues, we might need to add:
// quarkus.native.resources.includes=version.properties
// to the application.properties.
