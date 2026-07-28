# bsh-plugin

BeanShell (`.bsh`) tooling: an IntelliJ Platform plugin for language support and an
in-editor debugger, plus a JVM debug agent that speaks the [Debug Adapter
Protocol][dap] so VS Code, Neovim and Eclipse can debug BeanShell too.

One Gradle build, four subprojects:

```
plugin/            :plugin              IntelliJ plugin -- language support and the debugger UI
agent/instrument/  :agent:instrument    bsh-debug-agent -- premain + the ASM transformer, shaded
agent/hook/        :agent:hook          bsh-debug-hook  -- the class instrumented BeanShell calls into
agent/samples/     :agent:samples       debugger fixtures; nothing ships from here
agent/checks/      --                   end-to-end agent checks, as bash scripts
editors/           --                   VS Code extension, Neovim/Eclipse configs for the DAP transport
docs/              repository-wide docs
```

## The two pieces

**The IntelliJ plugin** ([`plugin/`](plugin/README.md)) adds BeanShell language
support to any IntelliJ-based IDE — syntax highlighting, a full AST parser, code
completion, navigation, running `.bsh` scripts, and Maven `pom.xml` injection.

**The debug agent** ([`agent/README.md`](agent/README.md)) is a JVM agent that
instruments `bsh.Interpreter` so BeanShell scripts can be debugged at the source
level, without modifying the script or the library that embeds it. The IntelliJ
plugin bundles it and talks to it over a native protocol; the same agent also
speaks DAP, so it works as a standalone debug adapter for editors that have their
own DAP client — see [`editors/vscode/`](editors/vscode/README.md),
[`editors/neovim/`](editors/neovim/README.md) and
[`editors/eclipse/`](editors/eclipse/README.md).

## Building

Run from the repository root:

```bash
JAVA_HOME=<jdk17+> ./gradlew build              # everything, with tests
JAVA_HOME=<jdk17+> ./gradlew :plugin:test
./gradlew :agent:instrument:shadowJar           # the agent jar alone
```

The plugin needs **JDK 17+** (Gradle refuses less) and compiles Kotlin to 21. The
agent targets **Java 8**, because it loads into whatever JVM hosts BeanShell.

```bash
./agent/checks/run-all.sh                       # the agent, end to end
```

`agent/checks/` runs what a JVM test cannot arrange from inside itself: a real
`mvn` process, a JVM launched with `-javaagent`, and two processes talking over
the debug socket. See [`agent/checks/README.md`](agent/checks/README.md).

## Where to read first

- [`agent/README.md`](agent/README.md) — the debug agent: why an agent rather than
  JDWP, how the transformer works, the landmines, the wire protocol.
- [`plugin/README.md`](plugin/README.md) — the IntelliJ plugin's features,
  requirements and installation.
- [`plugin/docs/ARCHITECTURE.md`](plugin/docs/ARCHITECTURE.md) — the language plugin
  (lexer, parser, PSI, resolution, completion).
- [`plugin/docs/DEBUGGING.md`](plugin/docs/DEBUGGING.md) — the IDE side of debugging,
  and which of the three instrumentation implementations runs.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the debug wire protocol, in full.
- [`docs/FUTURE_WORK.md`](docs/FUTURE_WORK.md) — open work, ordered by what blocks
  what.
- [`docs/BEANSHELL-DEFECTS.md`](docs/BEANSHELL-DEFECTS.md) — upstream bugs in
  BeanShell 2.0b6 that a debugger runs into.

## License

Apache License 2.0 — see [`plugin/LICENSE`](plugin/LICENSE) and
[`plugin/NOTICE`](plugin/NOTICE). BeanShell itself is developed by the
[Apache BeanShell](https://beanshell.github.io/) project.

[dap]: https://microsoft.github.io/debug-adapter-protocol/specification
