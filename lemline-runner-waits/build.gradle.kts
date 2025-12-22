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
    implementation(project(":lemline-runner-common"))

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)

    // Quarkus core (for CDI annotations)
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)
    testImplementation(testFixtures(project(":lemline-common")))
    testImplementation(testFixtures(project(":lemline-core")))
    testImplementation(testFixtures(project(":lemline-runner-waits")))
    testImplementation(testFixtures(project(":lemline-runner-common")))

    // Test Fixtures
    testFixturesImplementation(libs.bundles.kotlinxEcosystem)
    testFixturesImplementation(testFixtures(project(":lemline-common")))
    testFixturesImplementation(testFixtures(project(":lemline-core")))
    testFixturesImplementation(project(":lemline-runner-common"))
}
