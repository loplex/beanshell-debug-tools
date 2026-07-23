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

        // Java plugin, used (optionally) for resolving Java classes referenced from
        // scripts and for attaching the JVM debugger. Wired as an optional dependency.
        bundledPlugin("com.intellij.java")

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

// --- Maven core extension (Core-2) -----------------------------------------------
// A standalone, JDK-only jar shipped *inside* the plugin and dropped onto Maven's
// `-Dmaven.ext.class.path` when debugging an inline BeanShell <script> in a pom.xml.
// It rewrites the inline script in the POM model (with the IDE-instrumented text) and
// adds the debug-agent callback jar as a system-scoped plugin dependency. Compiled for
// Java 8 so it loads in any Maven JVM; it never reaches the plugin's own classpath.
val mavenExt: SourceSet by sourceSets.creating

dependencies {
    "mavenExtCompileOnly"("org.apache.maven:maven-core:3.6.3")
    "mavenExtCompileOnly"("org.codehaus.plexus:plexus-utils:3.3.0")
    "mavenExtCompileOnly"("javax.inject:javax.inject:1")

    // Unit-test the extension's model surgery against the light Maven model (no maven-core).
    testImplementation(mavenExt.output)
    testImplementation("org.apache.maven:maven-model:3.6.3")
    testImplementation("org.codehaus.plexus:plexus-utils:3.3.0")
}

tasks.named<JavaCompile>("compileMavenExtJava") {
    options.release.set(8)
}

// The debug agent (the only code in src/main/java) is loaded inside foreign JVMs — the forked
// BeanShell interpreter and, for pom.xml debugging, the Maven JVM (which may run an older JDK).
// Keep its bytecode at Java 8 so it loads regardless of the target runtime.
tasks.named<JavaCompile>("compileJava") {
    options.release.set(8)
}

val mavenExtJar by tasks.registering(Jar::class) {
    archiveBaseName.set("bsh-maven-ext")
    archiveVersion.set("")
    from(mavenExt.output.classesDirs)
    from("src/mavenExt/resources")
}

// Ship the extension jar as a plugin resource; extracted to a temp file at debug time.
// Kept at a top-level resource path (not under the cz/loplex/... package tree) so the
// plugin's jar assembly does not drop it.
tasks.named<Copy>("processResources") {
    from(mavenExtJar) {
        into("beanshell")
    }
}
