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

// Native images cannot enumerate classpath directories at runtime.
val sqliteMigrations = fileTree("src/main/resources/db/migration/messaging/sqlite") {
    include("*.sql", "*.sql.conf")
}
val sqliteMigrationIndex = layout.buildDirectory.file("generated/sqlite-resources/sqlite-messaging-migrations.txt")
val indexSqliteMigrations by tasks.registering {
    inputs.files(sqliteMigrations).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(sqliteMigrationIndex)
    doLast {
        val names = sqliteMigrations.files.map { it.name }.sorted()
        check(names.isNotEmpty() && names.distinct().size == names.size)
        val index = sqliteMigrationIndex.get().asFile
        index.parentFile.mkdirs()
        index.writeText(names.joinToString("\n", postfix = "\n"))
    }
}
tasks.processResources {
    from(indexSqliteMigrations)
}
