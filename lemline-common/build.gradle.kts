plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)

    id("java-test-fixtures")
}

dependencies {
    // Apply the kotlinx bundle of dependencies from the version catalog (`gradle/libs.versions.toml`).
    implementation(libs.bundles.kotlinxEcosystem)

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Serverless Workflow SDK
    implementation("io.serverlessworkflow:serverlessworkflow-api:7.0.0.Final")
    implementation("io.serverlessworkflow:serverlessworkflow-impl-core:7.0.0.Final")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    // Add Kotlin Serialization
    implementation(libs.kotlinxSerializationJson)

    // UUID Creator
    implementation(libs.uuidCreator)

    // Testing
    testImplementation(kotlin("test"))

    // Tests Fixtures
    testFixturesImplementation(enforcedPlatform(libs.kotest.bom))
    testFixturesImplementation("io.kotest:kotest-runner-junit5")
}
