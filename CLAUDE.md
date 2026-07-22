# CLAUDE.md

Guidance for Claude Code when working in this repository. User-facing docs are in
[README.md](README.md); design detail is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
and [docs/DEBUGGING.md](docs/DEBUGGING.md).

## What this is

An IntelliJ Platform plugin providing BeanShell (`.bsh`) language support. Kotlin,
with one pure-Java class (the debug agent). Targets IntelliJ IDEA **2025.3**.

## Commands

```bash
./gradlew compileKotlin   # fast compile check
./gradlew test            # test suite (BasePlatformTestCase + plain unit tests)
./gradlew buildPlugin     # distributable ZIP -> build/distributions
./gradlew runIde          # sandbox IDE (GUI)
```

## Key conventions & gotchas

- **Grammar target: BeanShell 2.0b6.** The lexer/parser are **hand-written**
  (transcribed from `bsh.jjt`); IntelliJ can't use the JavaCC grammar directly.
  Do not try to wire `.jjt`/`.jj` into the build.
- **Optional integrations must not hard-depend.** Java, XML and Spellchecker are
  wired through *optional* `<depends optional="true" config-file="...">` descriptors
  (`bsh-java.xml`, `bsh-xml.xml`, `bsh-spellchecker.xml`) plus availability guards
  in code (`BshJavaSupport`, class/type-id lookups). A non-optional `<depends>` on
  a missing plugin breaks plugin loading — including in tests. Follow the existing
  pattern.
- **The debug agent (`debug/agent/BshDebugAgent.java`) is pure JDK.** No Kotlin,
  IntelliJ, or compile-time BeanShell deps — it runs in the forked JVM. Keep it so.
- **Maven inline-script list** is data, not code:
  `src/main/resources/beanshell/maven-scripts.txt` (`artifactId | tag | direct|nested`).
- All feature EPs are registered in `src/main/resources/META-INF/plugin.xml`.

## Testing notes

- PSI/feature tests extend `BasePlatformTestCase`; pure logic (lexer, step logic)
  uses plain JUnit.
- Deterministic **Java resolution** tests add a real Java class with
  `myFixture.addFileToProject("Foo.java", ...)` so `JavaPsiFacade` resolves it.
- The **debug transport** is tested end-to-end by instrumenting a script and
  running it through the bundled BeanShell in a subprocess, acting as the IDE over
  a socket. Live XDebug UI is only verifiable via `runIde`.

## Package map

`highlight`, `editor`, `formatting`, `structure`, `navigation`, `completion`,
`inspection`, `intention`, `findusages`, `template`, `injection`, `run`, `debug`,
`reference` (resolution / type inference), `psi`, `lexer`, `parser`.
