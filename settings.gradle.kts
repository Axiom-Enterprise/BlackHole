import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = """
        
        =====================[ ERROR ]=====================
         The Purpur project directory is not a properly cloned Git repository.
         
         In order to build Purpur from source you must clone
         the Purpur repository using Git, not download a code
         zip from GitHub.
         
         Built Purpur jars are available for download at
         https://purpurmc.org/downloads
         
         See https://github.com/PurpurMC/Purpur/blob/HEAD/CONTRIBUTING.md
         for further information on building and modifying Purpur.
        ===================================================
    """.trimIndent()
    error(errorText)
}

rootProject.name = "axiom"
for (name in listOf("purpur-api", "purpur-server", "axiom-diagnostics-viewer")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}

optionalInclude("test-plugin")

fun optionalInclude(name: String, op: (ProjectDescriptor.() -> Unit)? = null) {
    val settingsFile = file("$name.settings.gradle.kts")
    if (settingsFile.exists()) {
        apply(from = settingsFile)
        findProject(":$name")?.let { op?.invoke(it) }
    } else {
        settingsFile.writeText(
            """
            // Uncomment to enable the '$name' project
            // include(":$name")

            """.trimIndent()
        )
    }
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val purpurChannel = providers.gradleProperty("channel").get().trim()
    val purpurBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    // Axiom - local builds used "$mcVersion.local-SNAPSHOT"; the ".local" dotted segment is parsed as a version
    // component by plugins doing semver-style version detection (e.g. Images) and threw "unknown minor version".
    // Use a hyphenated pre-release suffix instead so /version reads cleanly and stays parser-friendly.
    val versionString = if (purpurBuildNumber == null) {
        "$mcVersion-DEV-SNAPSHOT"
    } else {
        "$mcVersion.build.$purpurBuildNumber-${purpurChannel.lowercase()}"
    }
    version = versionString
}
