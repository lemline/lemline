// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

// Include subprojects in the build.
include(":lemline-common")
include(":lemline-messages-proto")
include(":lemline-core")
include(":lemline-runner-common")
include(":lemline-runner-waits")
include(":lemline-runner-retries")
include(":lemline-runner-schedules")
include(":lemline-runner-parents")
include(":lemline-runner-forks")
include(":lemline-runner-listeners")
include(":lemline-runner-failures")
include(":lemline-runner-definitions")
include(":lemline-runner-gateway")
include(":lemline-runner-cli")
include(":lemline-runner-messaging-pgmq")
include(":lemline-runner")

rootProject.name = "lemline"
