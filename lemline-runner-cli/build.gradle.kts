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
    implementation(project(":lemline-runner-definitions"))
    implementation(project(":lemline-runner-schedules"))
    implementation(project(":lemline-runner-listeners"))
    implementation(project(":lemline-runner-gateway"))

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)

    // Quarkus core (for CDI annotations and Quarkus runtime)
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-picocli")
    implementation("io.quarkus:quarkus-kotlin")

    // Serverless Workflow SDK (for Workflow type)
    implementation(libs.serverlessworkflow.api)
    implementation(libs.serverlessworkflow.impl.core)

    // Jackson for JSON/YAML processing
    implementation(libs.jackson.bom)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // SemVer library (for version comparison)
    implementation(libs.javaSemver)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)
    testImplementation(testFixtures(project(":lemline-core")))

    // Serverless Workflow API for mock types
    testImplementation(libs.serverlessworkflow.api)

    // Access to lemline-runner classes for tests (WorkflowCommandEmitter, LifecycleEventHookImpl)
    testImplementation(project(":lemline-runner"))

    // MicroProfile Config for ConfigCommand tests
    testImplementation("org.eclipse.microprofile.config:microprofile-config-api:3.1")
}
