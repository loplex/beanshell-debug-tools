# CLAUDE.md

Guidance for Claude Code across this repository. This file covers the **repository
layout and the rules that hold everywhere**; the plugin has its own
[`plugin/CLAUDE.md`](plugin/CLAUDE.md) with the conventions specific to it.

## Layout — one Gradle build, three subprojects

The root project is a container: wrapper, version catalog, build-wide properties,
no sources of its own.

```
plugin/            :plugin              IntelliJ plugin -- language support and the debugger UI
agent/instrument/  :agent:instrument    bsh-debug-agent -- premain + the ASM transformer, shaded
agent/hook/        :agent:hook          bsh-debug-hook  -- the class instrumented BeanShell calls into
agent/samples/     :agent:samples       debugger fixtures; nothing ships from here
docs/              repository-wide docs
```

```bash
JAVA_HOME=<jdk17+> ./gradlew build              # everything, with tests
JAVA_HOME=<jdk17+> ./gradlew :plugin:test
./gradlew :agent:instrument:shadowJar           # the agent jar alone
```

The plugin needs **JDK 17+** (Gradle refuses less) and compiles Kotlin to 21. The
agent targets **Java 8**, because it loads into whatever JVM hosts BeanShell — a
per-task `options.release`, not a build-wide property.

**The agent is a separate subproject on purpose.** It has to stay independently
publishable: once it speaks DAP it is a debug adapter that VS Code, Neovim or
Eclipse can attach to, and none of them will take it out of an IntelliJ plugin ZIP.
It does not need a second build tool to be that — a Gradle subproject has its own
coordinates and publishes just as well.

## Where to read first

- [`agent/README.md`](agent/README.md) — the debug agent: why an agent rather than
  JDWP, how the transformer works, the landmines, the wire protocol. Anything about
  the agent starts here.
- [`plugin/docs/ARCHITECTURE.md`](plugin/docs/ARCHITECTURE.md) — the language plugin
  (lexer, parser, PSI, resolution, completion).
- [`plugin/docs/DEBUGGING.md`](plugin/docs/DEBUGGING.md) — the IDE side of debugging,
  and which of the three instrumentation implementations runs.
- [`docs/FUTURE_WORK.md`](docs/FUTURE_WORK.md) — open work, ordered by what blocks
  what. Read before starting anything.
- [`docs/BEANSHELL-DEFECTS.md`](docs/BEANSHELL-DEFECTS.md) — upstream bugs in
  BeanShell 2.0b6 that a debugger runs into.

**Two different things are called "the agent".** `agent/` is the ASM-instrumenting
JVM agent (`cz.loplex.bsh.*`), the default mechanism. `plugin/…/debug/agent/BshDebugAgent.java`
is the in-plugin hook class used by the *source-rewriting* fallback
(`cz.loplex.intellij.bsh.debug.agent`). Which one is meant follows from the package.

## Commit messages

- **Imperative, capitalized subject, no type prefixes.** Match the existing history:
  `Add …`, `Move …`, `Document …`. Do **not** use Conventional Commits
  (`feat:`/`fix:`) — that only pays off with release tooling this repo doesn't have.
- **A bugfix starts with the verb `Fix …`** (e.g. `Fix "';' expected" on a trailing
  return expression`). That flags the fix within the same verb-first style, without a
  `fix:` tag.
- **No `Co-Authored-By` trailer.** The maintainer reviews and edits every commit before
  pushing.
