plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)

    id("java-test-fixtures")
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
    val documentationWorkflows = layout.projectDirectory.dir("../lemline-docs/snippets/workflows")
    inputs.dir(documentationWorkflows).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("lemline.docs.workflows", documentationWorkflows.asFile.absolutePath)
    val exampleDirectories = listOf(
        "src/main/resources/examples",
        "src/test/resources/examples",
        "../lemline-runner/src/test/resources/examples",
        "../examples/workflows",
        "../docker/namespace-isolation/workflows",
        "../.claude/skills/serverless-workflow-writer/assets",
    ) + listOfNotNull(providers.gradleProperty("workflowExampleDir").orNull?.let { rootProject.file(it).absolutePath })
    inputs.files(exampleDirectories.map { fileTree(it) { include("**/*.yaml", "**/*.yml", "**/*.json") } })
        .withPropertyName("workflowExamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("lemline.test.exampleDirectories", exampleDirectories.joinToString(File.pathSeparator))
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

tasks.register<JavaExec>("jsonBenchmark") {
    description = "Compare JSON expression and workflow execution time and allocations in three fresh JVMs"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.lemline.core.benchmarks.JsonRuntimeBenchmark")
    jvmArgs("-Xms512m", "-Xmx512m")
}

dependencies {
    implementation(project(":lemline-common"))
    implementation(project(":lemline-messages-proto"))

    // Add kotlinx-coroutines-slf4j for MDC context support in coroutines
    implementation(libs.kotlinxCoroutinesSlf4j)

    // Apply the kotlinx bundle of dependencies from the version catalog (`gradle/libs.versions.toml`).
    implementation(libs.bundles.kotlinxEcosystem)

    // Open Workflow SDK
    implementation(libs.serverlessworkflow.api)
    implementation(libs.serverlessworkflow.impl.core)

    // Used directly by Lemline; these are no longer provided by the SDK implementation core.
    implementation("net.thisptr:jackson-jq:1.2.0")
    implementation("com.networknt:json-schema-validator:2.0.7")

    // Ktor client for native Kotlin HTTP client with coroutine support
    implementation(platform(libs.ktor.bom))
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-serialization-jackson")
    implementation("io.ktor:ktor-client-auth")

    // YAML support for Kotlin serialization
    implementation("com.charleskorn.kaml:kaml:0.104.0")

    // XML support for Kotlin serialization
    implementation("io.github.pdvrieze.xmlutil:core-jvmcommon:1.0.2.1")
    implementation("io.github.pdvrieze.xmlutil:serialization-jvm:1.0.2.1")

    // Auth0 for JWT token generation and validation
    implementation("com.auth0:java-jwt:4.6.2")

    // CloudEvents SDK for building CloudEvents (serialization done in lemline-runner)
    implementation(libs.cloudevents.core)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-framework-engine")
    testImplementation(libs.mockk)
    testImplementation("io.ktor:ktor-client-mock")

    // Logging for tests
    testImplementation("io.github.oshai:kotlin-logging-jvm:8.0.7")
    testImplementation("ch.qos.logback:logback-classic:1.6.6")

    // Tests Fixtures
    testFixturesImplementation(enforcedPlatform(libs.kotest.bom))
    testFixturesImplementation("io.kotest:kotest-runner-junit5")
    testFixturesImplementation("io.kotest:kotest-assertions-core")
    testFixturesImplementation("io.kotest:kotest-framework-engine")
    testFixturesImplementation(libs.bundles.kotlinxEcosystem)
    testFixturesImplementation(testFixtures(project(":lemline-common")))
    // Required for FullOrchestratorExecutor
    testFixturesImplementation(libs.serverlessworkflow.api)
    // Required for CloudEvent test data
    testFixturesImplementation(libs.cloudevents.core)
}
