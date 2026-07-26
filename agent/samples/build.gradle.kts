// Debugger fixtures: BeanShell scripts plus an embedded-host driver.
//
// A subproject rather than a loose directory so that DebugHost.java is compiled by every
// build. It used to be built by a hand-typed javac line, which meant it could rot against a
// BeanShell upgrade and nobody would find out until they next needed it.
//
// Nothing here ships. It exists to be run against the agent, both ways: the scripts through
// Interpreter.run() and DebugHost through Interpreter.eval(), which are two separate loops --
// an agent that hooks only one looks correct in the CLI and does nothing in a library.

import org.gradle.process.CommandLineArgumentProvider

plugins {
    java
}

group = "cz.loplex.bsh"
description = "Debugger fixtures for the BeanShell debug agent"

dependencies {
    implementation(libs.bsh)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

// The scripts source() each other by bare name (07_eval_and_source.bsh -> 07_aux.bsh), so the
// working directory has to be the script directory itself.
val scriptDir = layout.projectDirectory.dir("scripts")

// The shaded agent jar, for the instrumented run.
val agentJar = configurations.create("agentJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    agentJar(project(path = ":agent:instrument", configuration = "shadedJar"))
}

fun JavaExec.debugHost() {
    group = "verification"
    mainClass.set("DebugHost")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = scriptDir.asFile
    systemProperty("samples", ".")
}

tasks.register<JavaExec>("runHost") {
    description = "Run the eight embedded-host scenarios uninstrumented"
    debugHost()
}

tasks.register<JavaExec>("runHostWithAgent") {
    description = "Run the eight embedded-host scenarios under the instrumenting agent"
    debugHost()
    val agentArgument = agentJar.elements.map { "-javaagent:" + it.single().asFile.absolutePath }
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf(agentArgument.get()) })
}

// Every fixture, through Interpreter.run() rather than eval(). Takes -Pscript=NN_name.bsh.
tasks.register<JavaExec>("runScript") {
    group = "verification"
    description = "Run one fixture through the BeanShell CLI (-Pscript=01_basic.bsh)"
    mainClass.set("bsh.Interpreter")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = scriptDir.asFile
    val script = providers.gradleProperty("script").orElse("01_basic.bsh")
    argumentProviders.add(CommandLineArgumentProvider { listOf(script.get()) })
}
