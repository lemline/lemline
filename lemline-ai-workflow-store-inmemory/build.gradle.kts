plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":lemline-ai-workflow-common"))

    implementation(libs.bundles.kotlinxEcosystem)

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation(kotlin("test"))
}
