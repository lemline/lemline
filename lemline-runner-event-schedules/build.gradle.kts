plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":lemline-common"))
    implementation(project(":lemline-core"))
    implementation(project(":lemline-runner-common"))
    implementation(project(":lemline-runner-definitions"))
    implementation(project(":lemline-runner-listeners"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")
    implementation(libs.serverlessworkflow.api)
    implementation(libs.serverlessworkflow.impl.core)
    implementation(libs.cloudevents.core)

    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)
    testImplementation(project(":lemline-runner-schedules"))
    testImplementation(testFixtures(project(":lemline-common")))
    testImplementation(testFixtures(project(":lemline-core")))
    testImplementation(testFixtures(project(":lemline-runner-common")))
}
