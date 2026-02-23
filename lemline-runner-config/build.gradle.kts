plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":lemline-common"))
    implementation(project(":lemline-runner-common"))

    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-hibernate-validator")
}
