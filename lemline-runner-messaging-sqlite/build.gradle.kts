// SPDX-License-Identifier: BUSL-1.1
plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":lemline-common"))
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")
    implementation(libs.sqlite.jdbc)
    implementation("org.flywaydb:flyway-core")
    implementation(libs.smallrye.reactive.messaging.api)
    implementation("io.smallrye.reactive:smallrye-reactive-messaging-provider")
    implementation("io.smallrye.reactive:mutiny-zero-flow-adapters")
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.kotlinxCoroutinesReactive)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.smallrye.config:smallrye-config-core")
}

tasks.test {
    systemProperty("sqlite.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
