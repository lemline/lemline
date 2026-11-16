plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

tasks.register<JavaExec>("runManualConversation") {
    group = "application"
    description = "Run interactive manual test for the AI workflow conversation engine."
    mainClass.set("com.lemline.ai.workflow.core.manual.ManualConversationMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

dependencies {
    implementation(project(":lemline-ai-workflow-common"))

    implementation(libs.bundles.kotlinxEcosystem)

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation(kotlin("test"))
}
