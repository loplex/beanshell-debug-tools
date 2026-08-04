// The hook: the class instrumented BeanShell code calls into.
//
// Published to the bootstrap classloader at runtime, so it must be reachable from every
// classloader and must never shadow anything the host application uses. That is why this
// module declares NO dependencies -- not "none currently", but none possible without
// someone editing this file and noticing why the block is absent. Pure JDK plus
// reflection is the whole contract; even BeanShell is missing, since the agent has to
// work against any bsh build found in the target JVM.

plugins {
    java
}

group = "cz.loplex.bsh"
description = "Bootstrap-loaded hook invoked by the instrumented BeanShell interpreter"

// See agent/instrument/build.gradle.kts for why the toolchain forks an actual JDK 8 javac
// (no `--release` flag: that's a JDK 9+ flag javac 8 doesn't understand -- the toolchain's
// own source/target default already is 8), and why sourceCompatibility is declared here too:
// IntelliJ's Gradle sync doesn't evaluate `configureEach {}` blocks.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    // The instrument subproject embeds this jar under a fixed name; keeping the artifact
    // name off the project directory name makes that mapping obvious.
    archiveBaseName.set("bsh-debug-hook")
}
