import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // BeanShell interpreter, bundled into the plugin so scripts run out of the box.
    // This is the version published to Maven Central and matches the 2.0b6 grammar.
    implementation("org.apache-extras.beanshell:bsh:2.0b6")

    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Bundled spellchecker; the spellchecking strategy is wired via an
        // optional descriptor so the plugin still loads where it is absent.
        bundledModule("intellij.spellchecker")

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}
