// BeanShell debug agent.
//
// A -javaagent that instruments the BeanShell interpreter itself, so no modification of
// the debugged script (or of the library embedding BeanShell) is required. Deliberately
// version-independent: the transformer keys off the AST evaluation signature
//
//     eval(Lbsh/CallStack;Lbsh/Interpreter;)Ljava/lang/Object;
//
// which is the BeanShell AST contract and is stable across releases. No class is named
// explicitly and no BeanShell version is assumed.

plugins {
    java
    alias(libs.plugins.shadow)
}

group = "cz.loplex.bsh"
description = "Instruments bsh.Interpreter's AST evaluation to provide source-level debugging of BeanShell scripts"

dependencies {
    implementation(libs.asm)
    // BeanShell is NOT a dependency, not even a compile-only one. The agent must work
    // against any bsh build found in the target JVM, so every access to bsh types goes
    // through reflection. Adding it here would invite accidental compile-time coupling.
}

// Java 8: the agent has to load in whatever JVM the host library runs on, so the floor is
// set as low as the tooling allows.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    // Replace the plain jar rather than sit beside it as `-all`, and carry the artifact
    // name instead of the project directory name.
    archiveBaseName.set("bsh-debug-agent")
    archiveClassifier.set("")

    // ASM is relocated because the agent jar is appended to the bootstrap classloader
    // search: an un-relocated org.objectweb.asm there would shadow whatever ASM the host
    // application uses.
    relocate("org.objectweb.asm", "cz.loplex.bsh.agent.shaded.asm")

    manifest {
        attributes(
            "Premain-Class" to "cz.loplex.bsh.agent.BshAgentMain",
            "Agent-Class" to "cz.loplex.bsh.agent.BshAgentMain",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}

tasks.named("assemble") { dependsOn(tasks.shadowJar) }
