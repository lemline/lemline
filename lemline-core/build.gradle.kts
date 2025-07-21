plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)
}

/**
 * Configures the `test` task for the project.
 *
 * - **JUnit Platform**: Specifies that the JUnit Platform (used for JUnit 5) should be used to execute the tests.
 * - **Test Logging**:
 *   - Logs events for passed, skipped, and failed tests.
 *   - Displays standard output and error streams in the logs.
 *   - Ensures exceptions, stack traces, and root causes are logged.
 *   - Uses the full exception format for detailed debugging information.
 */
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

dependencies {
    implementation(project(":lemline-common"))

    // Apply the kotlinx bundle of dependencies from the version catalog (`gradle/libs.versions.toml`).
    implementation(libs.bundles.kotlinxEcosystem)

    // Serverless Workflow SDK
    implementation(libs.serverlessworkflow.api)
    implementation(libs.serverlessworkflow.impl.core)

    // Ktor client for native Kotlin HTTP client with coroutine support
    implementation(platform(libs.ktor.bom))
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-serialization-jackson")
    implementation("io.ktor:ktor-client-auth")

    // YAML support for Kotlin serialization
    implementation("com.charleskorn.kaml:kaml:0.82.0")

    // XML support for Kotlin serialization
    implementation("io.github.pdvrieze.xmlutil:core-jvmcommon:0.91.1")
    implementation("io.github.pdvrieze.xmlutil:serialization-jvm:0.91.1")

    // Auth0 for JWT token generation and validation
    implementation("com.auth0:java-jwt:4.4.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-framework-api")
    testImplementation(libs.mockk)
    testImplementation("io.ktor:ktor-client-mock")

    // Logging for tests
    testImplementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
}
