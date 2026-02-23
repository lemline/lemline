// SPDX-License-Identifier: BUSL-1.1
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.quarkus)
    idea
}

val quarkusGrpcGeneratedSourcesDir = layout.buildDirectory.dir("classes/java/quarkus-generated-sources/grpc")

sourceSets {
    named("main") {
        java.srcDir(quarkusGrpcGeneratedSourcesDir)
    }
}

idea {
    module {
        generatedSourceDirs.add(quarkusGrpcGeneratedSourcesDir.get().asFile)
    }
}

tasks.matching { it.name == "prepareKotlinBuildScriptModel" }.configureEach {
    dependsOn("quarkusGenerateCode")
}

dependencies {
    implementation(project(":lemline-common"))
    implementation(project(":lemline-core"))
    implementation(project(":lemline-runner-common"))
    implementation(project(":lemline-runner-definitions"))
    implementation(project(":lemline-runner-schedules"))

    implementation(libs.bundles.kotlinxEcosystem)

    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-grpc")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-smallrye-jwt")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-jdbc-mysql")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-micrometer")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("com.google.protobuf:protobuf-kotlin")
    implementation(libs.serverlessworkflow.api)

    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(testFixtures(project(":lemline-runner-common")))
    testImplementation(testFixtures(project(":lemline-common")))
    testImplementation(testFixtures(project(":lemline-core")))
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}

// This is a library module — only use the Quarkus plugin for gRPC code generation.
// Disable Quarkus augmentation/build tasks that require a full application classpath.
tasks.matching {
    it.name.startsWith("quarkusBuild") ||
        it.name.startsWith("quarkusDev") ||
        it.name.startsWith("quarkusAppPartsBuild") ||
        it.name.startsWith("quarkusDependencies")
}.configureEach {
    enabled = false
}
