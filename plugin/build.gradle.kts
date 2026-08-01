import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // BeanShell interpreter, bundled into the plugin so scripts run out of the box.
    // This is the version published to Maven Central and matches the 2.0b6 grammar.
    implementation(libs.bsh)

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

        // Maven plugin, used (optionally) for the BeanShell-enhanced Maven run
        // configuration that debugs inline <script> blocks. Wired as an optional
        // dependency (bsh-maven.xml) so the plugin still loads where Maven is absent.
        bundledPlugin("org.jetbrains.idea.maven")
    }
}

// Since 2025.3, intellijIdea() bundles what used to be Ultimate-only plugins (this
// plugin never asked for any of them). The Vue.js plugin's VueLspServerSupportProvider
// intermittently throws during lazy init in a headless test sandbox, and doHighlighting()
// (used by feature tests) triggers every registered extension point -- so the logged
// error fails whichever test happens to be running at the time, not a real regression.
tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
    disabledPlugins.add("org.jetbrains.plugins.vue")
}

// Cheap, no-IDE-download sanity check (plugin.xml since-build, Java/Kotlin compatibility
// levels, stray Kotlin stdlib/coroutines deps, ...) -- not wired to `check` by default.
tasks.named("check") {
    dependsOn("verifyPluginProjectConfiguration")
}

// Renders plugin.xml's <description> (the Marketplace listing text) as a standalone HTML
// file, so it can be reviewed in a browser -- exactly as JetBrains Marketplace will show it --
// without publishing anything. Not wired to `build`; run it on demand after editing the
// description.
tasks.register("renderMarketplaceDescription") {
    group = "documentation"
    description = "Renders plugin.xml's <description> as a standalone HTML file for previewing the Marketplace listing"

    val pluginXml = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml")
    val outputFile = layout.buildDirectory.file("marketplace-description.html")
    inputs.file(pluginXml)
    outputs.file(outputFile)

    doLast {
        val description = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pluginXml.asFile)
            .getElementsByTagName("description")
            .item(0)
            .textContent
            .trim()

        val html = """
            |<!doctype html>
            |<html>
            |<head>
            |<meta charset="utf-8">
            |<title>BeanShell Language Support -- Marketplace description preview</title>
            |<style>
            |  body { max-width: 800px; margin: 2rem auto; padding: 0 1rem;
            |         font-family: -apple-system, "Segoe UI", Roboto, sans-serif; line-height: 1.5; }
            |  img { max-width: 100%; border: 1px solid #ccc; }
            |  code { background: #f0f0f0; padding: 0 .3em; border-radius: 3px; }
            |</style>
            |</head>
            |<body>
            |$description
            |</body>
            |</html>
            |
        """.trimMargin()

        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(html)
        logger.lifecycle("Marketplace description preview written to file://${file.absolutePath}")
    }
}

// The project-level IntelliJ Platform Gradle Plugin extension: distinct from the
// dependencies-scoped `intellijPlatform { }` block above (a different receiver type,
// `IntelliJPlatformExtension` vs. `IntelliJPlatformDependenciesExtension`) despite the
// identical name.
intellijPlatform {
    pluginConfiguration {
        // "253" = the 2025.3 build number branch. Left open-ended on the upper
        // end, per JetBrains' own guidance, since nothing here is known to break
        // on newer IDEs; see plugin/CLAUDE.md for the targeted platform version.
        ideaVersion {
            sinceBuild = "253"
        }

        // Computed eagerly (not via a lazy Provider chain) on purpose: a lambda
        // referencing the `changelog` extension captures the whole Project, which
        // the configuration cache can't serialize. `-Pversion` is already resolved
        // by the time this line runs, so there is nothing to gain from laziness here.
        // Reads CHANGELOG.md's "[Unreleased]" section by default, or the section
        // matching the release version once release.yml patches it in. See
        // docs/RELEASING.md.
        changeNotes = with(changelog) {
            renderItem(
                (getOrNull(project.version.toString()) ?: getUnreleased())
                    .withHeader(false)
                    .withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }
    }

    // Both blocks read from environment variables rather than Gradle properties
    // because their values are secrets injected by release.yml, never committed.
    // See docs/RELEASING.md for how to generate and register them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
}

// --- The instrumenting debug agent -----------------------------------------------
// Shipped inside the plugin and extracted at debug time, exactly like the Maven core
// extension below. It is a resource rather than a lib/ jar on purpose: the agent's classes
// belong in the debugged JVM, and putting them on the plugin's own classpath would give the
// IDE a second, shaded copy of ASM for no reason.
val agentJar = configurations.create("agentJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    agentJar(project(path = ":agent:instrument", configuration = "shadedJar"))
}

// The Gradle project is named after its directory, so the distribution would be
// `plugin-<version>.zip`. Name it for the plugin instead.
//
// The two tidier-looking levers are both wrong, measured rather than assumed:
// `intellijPlatform.pluginConfiguration.name` also rewrites <name> in plugin.xml — the name
// shown on the Marketplace — and `base.archivesName` renames only the intermediate
// instrumented jar, leaving the distribution alone and producing three differently named jars
// in build/libs. The jar and directory inside the ZIP stay `plugin`; they are invisible to
// users and the only way to change them is renaming the Gradle project, which would cost the
// `:plugin:` task paths.
tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("intellij-beanshell")
}

// --- Maven core extension (Core-2) -----------------------------------------------
// A standalone, JDK-only jar shipped *inside* the plugin and dropped onto Maven's
// `-Dmaven.ext.class.path` when debugging an inline BeanShell <script> in a pom.xml.
// It rewrites the inline script in the POM model (with the IDE-instrumented text) and
// adds the debug-agent callback jar as a system-scoped plugin dependency. Compiled for
// Java 8 so it loads in any Maven JVM; it never reaches the plugin's own classpath.
val mavenExt: SourceSet = sourceSets.create("mavenExt")

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

val mavenExtJar = tasks.register<Jar>("mavenExtJar") {
    description = "Packages the Maven core extension that rewrites inline BeanShell scripts for debugging"
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
    // Renamed to drop the version, so the runtime lookup is a constant rather than a search.
    from(agentJar) {
        into("beanshell")
        rename { "bsh-debug-agent.jar" }
    }
}
