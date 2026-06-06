plugins {
    application
    id("com.gradleup.shadow") version "8.3.5"
}

description = "Self-hosted viewer for Axiom diagnostics reports"

dependencies {
    implementation("io.javalin:javalin:6.3.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass = "org.bxteam.axiom.diagnostics.viewer.ViewerMain"
}

tasks.shadowJar {
    archiveBaseName = "axiom-diagnostics-viewer"
    archiveClassifier = "all"
    mergeServiceFiles()
}

// This module is a standalone web service — it must not be published to the Purpur maven.
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
tasks.withType<PublishToMavenLocal>().configureEach { enabled = false }
