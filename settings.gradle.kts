import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

// The root project is a container only: it carries the wrapper, the version catalog
// and the build-wide properties, and holds no sources of its own. Every deliverable is
// a subproject, so `settings.gradle.kts` lives here rather than next to the plugin.
rootProject.name = "bsh-plugin"

include(":plugin")
include(":agent:hook")
include(":agent:instrument")
include(":agent:samples")

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
