// The agent proper: entry point plus the ASM transformer.
//
// Instruments bsh.Interpreter's AST evaluation so BeanShell code can be debugged without
// rewriting the script. Version-independent by construction: the transformer keys off the
// AST contract
//
//     eval(Lbsh/CallStack;Lbsh/Interpreter;)Ljava/lang/Object;
//
// and names no BeanShell class, so nodes added or renamed between releases need no change
// here.

plugins {
    java
    alias(libs.plugins.shadow)
}

group = "cz.loplex.bsh"
description = "Instruments bsh.Interpreter's AST evaluation to provide source-level debugging of BeanShell scripts"

// The hook jar travels inside this one as a resource. Resolved through its own
// configuration rather than a cross-project task reference, so the file arrives as a
// dependency artifact and the configuration cache stays valid.
val hookJar = configurations.create("hookJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation(libs.asm)

    // compileOnly, not implementation: the hook is needed to compile but must not be
    // shaded in. Its classes belong only inside the nested jar, so that the system
    // classloader cannot define a second copy alongside the bootstrap one -- two copies
    // means two sets of static state, with the instrumented interpreter using one and the
    // agent configuring the other.
    compileOnly(project(":agent:hook"))
    hookJar(project(":agent:hook"))

    // BeanShell is deliberately absent, not even compile-only: the agent must work against
    // any bsh build in the target JVM, so listing it here would only invite accidental
    // compile-time coupling.
}

// Java 8: the agent loads into whatever JVM the host library runs on, so the floor is set
// as low as the tooling allows.
//
// Both lines matter: `release` is what javac actually enforces (rejects newer bytecode AND
// newer source syntax); `sourceCompatibility`/`targetCompatibility` is what IntelliJ's Gradle
// sync reads to set the module's language level -- it does not evaluate `configureEach {}`
// blocks, so without this line the IDE assumes the project default (21) and its inspections
// suggest syntax this module can't actually compile.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveBaseName.set("bsh-debug-agent")
    archiveClassifier.set("")

    // As a whole file, not exploded -- BshAgentMain extracts /bsh-debug-hook.jar and hands
    // it to appendToBootstrapClassLoaderSearch, which takes a JarFile.
    from(hookJar) { rename { "bsh-debug-hook.jar" } }

    // ASM is relocated so the agent never shadows whatever ASM the host application uses.
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

// The shaded jar, offered to other subprojects by name. The default runtime variant carries
// the plain jar -- no ASM, no nested hook -- which would fail at premain rather than at build
// time, so consumers have to ask for this one explicitly.
val shadedJar = configurations.create("shadedJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(shadedJar.name, tasks.shadowJar)
}
