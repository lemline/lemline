// SPDX-License-Identifier: BUSL-1.1
plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)

    id("java-test-fixtures")
}

dependencies {
    implementation(project(":lemline-common"))
    implementation(project(":lemline-core"))
    implementation(project(":lemline-runner-common"))

    implementation(libs.bundles.kotlinxEcosystem)

    // Quarkus platform BOM
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Quarkus dependencies
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-scheduler")

    // Serverless Workflow SDK (for ForkTask type)
    implementation(libs.serverlessworkflow.api)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)
    testImplementation(testFixtures(project(":lemline-common")))
    testImplementation(testFixtures(project(":lemline-core")))
    testImplementation(testFixtures(project(":lemline-runner-forks")))
    testImplementation(testFixtures(project(":lemline-runner-common")))

    // Test Fixtures
    testFixturesImplementation(libs.bundles.kotlinxEcosystem)
    testFixturesImplementation(testFixtures(project(":lemline-common")))
    testFixturesImplementation(testFixtures(project(":lemline-core")))
    testFixturesImplementation(project(":lemline-runner-common"))
}
