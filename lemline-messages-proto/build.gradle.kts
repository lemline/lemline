plugins {
    id("buildsrc.convention.kotlin-jvm")
    `java-library`
    alias(libs.plugins.wire)
}

dependencies {
    api(libs.wire.runtime)
}

wire {
    kotlin {
        enumMode = "sealed_class"
    }
}
