# Future work

Ideas, known limitations and unfinished work worth revisiting, across the whole
repository. Not scheduled — a parking lot so they are not lost to conversation
history.

Ordered by what blocks what, not by size. **Nothing here blocks a release** — the
agent ships inside the plugin and the fixtures are in the repository.

---

## Threads — done

Two script threads are told apart and suspended independently as of protocol 3. Kept here as
a record of where the work actually was, since the earlier estimate in this file had it in the
wrong place.

**The protocol was the small part**, as predicted: a thread id and name on `STOPPED`, a thread
id on every command, and request ids on the four that expect a reply. Fully specified in
[`PROTOCOL.md`](PROTOCOL.md).

**The hook was the work**, also as predicted, and the structural change was the one that
mattered: a suspended thread used to read its own commands off the socket, which cannot work
once two are suspended — only one can be the reader. So the agent gained a dedicated daemon
reader thread that demultiplexes into per-thread mailboxes, and everything that was static
(`frames`, `interpreter`, `handles`, `runMode`) became per-thread. The old single lock, which
was held for the whole of a stop and was therefore *the* reason two threads could not both be
suspended, now guards only the moments bytes go on the wire.

**The suspend policy turned out not to be a choice.** The plan was to decide between stopping
one thread and stopping all; in fact an instrumenting agent can only stop a thread where it
calls the hook, so "suspend all" would mean "stop the others whenever they next reach a
statement" — a different guarantee with the same name. Only the thread that hit the breakpoint
suspends, and that is documented as the honest scope rather than offered as an option.

**On the IDE side** `mode`, `stepDepth`, `currentDepth` and the frames became per-thread,
`XSuspendContext.getExecutionStacks()` populates the Threads combo with every suspended thread,
and the request channel is keyed by request id. That last change also retired an old hazard: a
timed-out request used to poison the channel for good, because its late reply would have been
handed to the next request as its own answer.

The **rewriting fallback** speaks protocol 3 too — it reports its thread id and echoes request
ids, so the IDE needs no special case — but still holds one lock per stop, so a second thread
waits there rather than reporting alongside. That path exists to need nothing at all, and
independent suspension needs a reader thread of its own.

Exercised by `agent/checks/05-two-script-threads.sh` against
`agent/samples/scripts/06_callbacks_threads.bsh`.

---

## `_bshThis…` on a scripted instance — decided: keep it

Expanding an instance of a class declared in a script shows a `_bshThis…` field
holding a `bsh.XThis` — BeanShell's back-reference to the object's namespace. It was
listed here as noise to filter out. **It stays**: it is the object's namespace, which
is the one place the script's own view of the instance is visible, and a debugger for
a scripting language is exactly where that belongs. Nothing to do, recorded so it is
not "tidied away" later.

Where the same reasoning argues for *more*: anywhere the UI can show what BeanShell
knows and Java reflection does not — a closure's captured namespace, the interpreter's
own `global` namespace, a `This` returned to Java by a script.

## DAP as a second transport — done

Available as `-Dbsh.debug.protocol=dap`. The native protocol remains the default and IntelliJ
is unaffected, which was the whole point: LSP4IJ's DAP client does not implement the `thread`
event, so routing IntelliJ through DAP would have lost the thread support the native path has.

It came out cheap for the reason predicted here — only the last step of the hook turns an
answer into bytes, so `DebugChannel` splits the transport off and everything above it is
written once. Adopting DAP's vocabulary first (`stackTrace`/`scopes`/`variables`/`evaluate`/
`setVariable`, handles as `variablesReference`) is what made it a serialization change rather
than a redesign.

Three translations live in `DapChannel` and are worth knowing about: frame ids have to be made
globally unique (DAP quotes them back without naming a thread), a DAP step is one request where
the native protocol has two commands, and DAP has a handshake this protocol deliberately lacks.
Specified in [`PROTOCOL.md`](PROTOCOL.md#9-relationship-to-dap), exercised by
`agent/checks/07-dap-transport.sh`.

Not implemented, and declared as such in the capabilities rather than claimed: conditional,
function and exception breakpoints, step-back, restart-frame, and `pause` — that last one being
the same structural limit as everywhere else, since a thread can only stop where it calls the
hook.

**The packaging this needed is done too**: [`editors/vscode/`](../editors/vscode/README.md) is
a VS Code extension with a `launch.json` contribution and a `.bsh` language id, for both
`attach` (to a JVM already running under the agent) and `launch` (the extension starts that JVM
itself, since the agent's own `launch` handler is a no-op — the script is already running either
way). [`editors/neovim/`](../editors/neovim/README.md) and
[`editors/eclipse/`](../editors/eclipse/README.md) cover the same transport for those editors,
the latter attach-only since Eclipse's generic DAP client has no scriptable way to launch the
debuggee itself.

[IJPL-83441]: https://youtrack.jetbrains.com/issue/IJPL-83441/Debug-adapter-protocol-support
[LSP4IJ]: https://github.com/redhat-developer/lsp4ij

---

## Smaller, independent

### Second JDWP channel for step-into Java — already works, no code needed

The original inline-debug plan left this as an optional item: a second, JDWP-based
debug channel (reuse `BshJavaDebugAttach`) so the developer can step *into* the Java
code a Maven-run script calls, in addition to line-stepping the script itself.

It turns out there is nothing to build. `BshMavenRunConfiguration` extends
`MavenRunConfiguration` and only augments `getState()` before delegating to the
super implementation, so the Debug executor wraps the forked Maven JVM in JDWP the
same way it would for any Maven run — the Java debug tab "appears for free", as the
class doc on `BshMavenRunConfiguration` already said. Verified by hand on
2026-07-29 in `./gradlew :plugin:runIde`: running `plugin/samples/maven/build-helper`'s
`bsh-property` goal under Debug opened both a `BeanShell (Maven)` session and a
`build-helper [install] (bsh)` Java session, and a breakpoint in `java.lang.String.length()`
(reached from the inline script's `project.getVersion().length()`) was actually hit,
not just a tab that appeared and did nothing.

`BshJavaDebugAttach` and the manual `-agentlib:jdwp` wiring in `BshDebugRunner.kt`
remain necessary for the standalone `.bsh` path, which runs a raw `GeneralCommandLine`
outside the platform's own Java-debugging support and so gets nothing "for free".

### End-to-end GUI test for the VS Code extension — done

`editors/vscode/src/test/` now covers what `agent/checks/07-dap-transport.sh` cannot: a real,
headless VS Code (`@vscode/test-electron` + Mocha, under Xvfb) starting an actual `launch`
session against the fixture in `src/test/fixtures/workspace/`, asserting on the DAP traffic via
a `DebugAdapterTracker`. The fixture, breakpoint line and evaluate expression are the same ones
`07` already proved work over `DapChannel` — deliberately, so the two checks are provably
exercising the same behavior one layer apart rather than two fixtures that could quietly drift.

**The one real gap `07` cannot reach**, and the reason this is `launch` rather than `attach`:
`dap-client.py` connects to a JVM the check already started, so it never touches this
extension's own `BshDebugAdapterDescriptorFactory.launch()` — the port allocation, the
`-javaagent` spawn, watching stdout for `DAP: listening`. `attach` would have covered `DapChannel`
a second time and nothing new.

**The handshake needed nothing hand-rolled.** `dap-client.py` drives `initialize` and
`configurationDone` itself because it *is* the client; `vscode.debug.startDebugging()` does that
internally, so the test only needed `session.customRequest()` for what a UI would otherwise
trigger by clicking — `stackTrace`, `scopes`, `variables`, `evaluate`, `continue`.

**What running it against a real client actually found**, and would not have shown up against
`dap-client.py`: `DapChannel` never sends a `terminated` or `exited` DAP event. When the script
runs to completion the JVM just exits and the socket drops, and `dap-client.py` never noticed
because it only speaks the protocol, not VS Code's own session bookkeeping. A real client has
to fall back to `onDidTerminateDebugSession` — the same signal `descriptorFactory.ts` already
uses to know when to stop waiting on the child process — rather than a message on the wire. Not
a bug to fix here, but worth knowing before adding another DAP client: nothing announces normal
completion, only the connection going away.

**"Headless" needed an extra push on a real Wayland desktop.** `xvfb-run` sets `DISPLAY` for its
virtual framebuffer, but leaves `WAYLAND_DISPLAY` alone, and Electron's Ozone platform selection
prefers a real Wayland compositor over that X11 display whenever one is reachable -- an
`ELECTRON_OZONE_PLATFORM_HINT=x11` env var was not enough to stop it. `runTest.ts` removes
`WAYLAND_DISPLAY` from the child's environment outright and passes `--ozone-platform=x11` as a
hard switch, so there is nothing left for Electron to prefer.

Run with `npm test` (`xvfb-run -a npm test` headless); resolves `AGENT_JAR`/`BSH_CLASSPATH`
through the same `:agent:samples:printPaths` Gradle task `agent/checks/lib.sh` uses. Documented
in [`editors/vscode/README.md`](../editors/vscode/README.md#testing).

### End-to-end GUI test for Neovim — done

`editors/neovim/tests/` drives `bsh-dap.lua` through a real, headless `nvim-dap` session
(`nvim --headless -l`, no display server needed) against the same fixture, breakpoint line and
evaluate expression `agent/checks/07-dap-transport.sh` and the VS Code test already prove work
over `DapChannel` — deliberately, for the same reason the VS Code fixture matches `07`'s: so the
three checks are provably exercising the same behavior, not three fixtures that could drift.
Covers what `07`'s `dap-client.py` cannot: `bsh-dap.lua`'s own `launch()` (the `jobstart` spawn,
the `DAP: listening` stdout watch), the Neovim counterpart to what the VS Code GUI test found for
`BshDebugAdapterDescriptorFactory.launch()`.

**No test framework needed.** `nvim -l` (a Lua-script entry point, not `-c`/`-u` sourcing) gets
the full API in headless mode, and `Session:request()` takes a plain callback — so the whole test
is `vim.wait()` polling a `done` flag per request, the same style nvim-dap's own test suite
(`spec/helpers.lua`) uses, rather than plenary or a coroutine wrapper.

**`dap.listeners.on_session` closes the same race the VS Code test's `on_close` finding warns
about.** It fires the moment `dap.run()` creates the session object, before the session has even
connected — attaching `session.on_close[...]` there, rather than after some later step, is what
makes the close-detection reliable rather than occasionally racing the connection tearing down
first. Confirms, on the Neovim side, what the VS Code test found on its: `DapChannel` never sends
a `terminated`/`exited` DAP event, so both clients only learn a session is over from a dropped
socket (`Session:close()`'s `on_close` here, `onDidTerminateDebugSession` there).

`nvim-dap` is fetched by `tests/run-tests.sh` into `tests/.deps/` (gitignored), pinned to a fixed
commit since the project carries no version tags — this test only exercises `bsh-dap.lua`
through it and vendors none of its code. Documented in
[`editors/neovim/README.md`](../editors/neovim/README.md#testing).

### End-to-end GUI test for Eclipse — decided: a manual runbook instead

Unlike VS Code and Neovim, [`editors/eclipse/`](../editors/eclipse/README.md) has no code of its
own to regress — it is a README, not a launcher; LSP4E's generic Debug Adapter launch
configuration is upstream code, configured entirely through its own UI dialog, and it is also
attach-only, so there is no launch step of ours to get wrong either. Automating it would mean
standing up a second build toolchain (Tycho, a p2 target platform, SWTBot — LSP4E ships via p2,
not Maven Central, so it can't join this Gradle build the lightweight way) just to re-verify that
*LSP4E* speaks DAP correctly against this agent, which
[`agent/checks/07-dap-transport.sh`](../agent/checks/07-dap-transport.sh)'s `dap-client.py`
already proves. Not worth a second build toolchain for coverage of code this repository doesn't
own.

What replaced it: [`editors/eclipse/README.md#manual-verification-runbook`](../editors/eclipse/README.md#manual-verification-runbook)
is a step-by-step checklist against the same shared fixture (now at
[`editors/eclipse/samples/script.bsh`](../editors/eclipse/samples/script.bsh)) — same breakpoint
line, same evaluate expression as `07` and the other two editors' tests — to run by hand after
touching the agent or the DAP transport, including the one thing worth watching that only shows
up against a real LSP4E session: what it does when `DapChannel` drops the socket instead of
sending a `terminated` event.

### GitHub Actions for builds — done

`.github/workflows/ci.yml` runs `./gradlew build` and `agent/checks/run-all.sh` on every push and
pull request, on `ubuntu-latest` with JDK 21 — nothing exotic needed since `mvn` and `python3` are
already on the image. The VS Code and Neovim extension tests (Xvfb + Electron, and `nvim -l`) are
not wired into CI yet — each needs its own runner setup (a display for the former, `nvim` and
`git` on `PATH` for the latter) and is left for a follow-up job.

### Scripted-class `super(...)` calls never resolve

Found by manually enabling the (disabled-by-default) `BshUnresolvedMethod` inspection against
the debugger fixtures in `agent/samples/scripts/` — it flagged `super(x, y)` in
`05_scripted_class.bsh`'s `Point3D` constructor as an unresolved method call.

`super` is not a keyword in `BshTokenTypes` — it lexes as a plain `IDENTIFIER`, so `super(x, y)`
parses as an ordinary `METHOD_INVOCATION` on a call named `"super"`. `BshParser.tryClassDeclaration`
only skips past `extends <name>` while parsing a class declaration; it never attaches that name to
the class node. `BshResolver` therefore has nothing to walk: `classMember` only collects elements
from a class's own subtree, and the resolve chain in `BshResolver.resolve` searches file/project
methods and classes by name, never a superclass's members. The result is that `super(...)` inside
*any* scripted class that subclasses another scripted class — a normal, supported BeanShell
pattern, not a fixture curiosity — resolves to nothing.

Not a release blocker: the inspection ships `enabledByDefault="false"` regardless, since BeanShell
scripts routinely call unmodeled Java library methods and built-in commands. But it is a distinct,
fixable gap rather than one more instance of that same disclaimer — the `extends` name is fully
available in the AST, the plugin just never records or walks it. Fixing it needs three things:
capture the superclass name on the class declaration node (parser), have `BshResolver`/
`classMember` follow it when a member is not found locally, and special-case bare
`super(...)`/`this(...)` constructor-delegation calls so they resolve to a constructor rather than
being looked up as a method literally named `super`.

### Language-inject `eval("...")`/`source("...")` string literals

Same review that found the `super` gap also flagged `ghost()` in `07_eval_and_source.bsh` as
unresolved — it is defined by `eval("ghost() { return \"ghost speaking\"; }");`, a method that
exists only inside a string literal, never as a PSI declaration. For that specific case the flag
is correct: the method genuinely does not exist anywhere resolution can see it.

But the argument here is a constant string, not one assembled at runtime from variables or I/O —
its contents are fully known at edit time, same as the rest of the file. The plugin already has
the mechanism for exactly this: `BshMavenInjector` (`injection/BshMavenInjector.kt`) injects the
`BeanShell` language into inline `<script>` text inside `pom.xml` via IntelliJ's `MultiHostInjector`,
after which the whole tool-chain — highlighting, resolution, even debugger breakpoints via
`BshLineBreakpointType`'s injected-host handling — treats that text as if it were a `.bsh` file.
Nothing structurally prevents doing the same for the string-literal argument of an `eval(...)` or
`source(...)` call: inject `BeanShell` into the literal, and `ghost()` becomes a real, resolvable
`METHOD_DECLARATION` inside it — Ctrl+Click, rename, and this same inspection all start working
across the injected boundary for free, the same way they already do for the Maven case.

Scope stays narrow by construction: only a literal string argument qualifies (no concatenation, no
variable). `eval(loadFromNetwork())` or `eval("go" + suffix + "()")` remain genuinely unresolvable,
same as today — the point is not to chase those, only to stop flagging the case where the source
text is sitting right there in the file.

### Resolve BeanShell's built-in commands (`print`, `exec`, `source`, …)

`BshUnresolvedMethodInspection` flags every bare call to a BeanShell built-in command —
`print(...)`, `exec(...)`, `source(...)`, and the rest of `bsh/commands/` in the interpreter jar —
because `BshResolver` only ever looks for a `METHOD_DECLARATION` in the current file or elsewhere
in the project. Built-in commands are not Java methods and carry no PSI of their own: BeanShell's
`NameSpace` resolves an unknown call name at runtime by searching the classpath for a
`bsh/commands/<name>.bsh` (or `.class`) resource, first under its default package, then under
whatever paths a script has added via `importCommands("some.package")`.

Two ways to close this, worth doing in order:

- **Cheap, immediate**: bundle a static list of command names for the BeanShell version(s) this
  plugin targets (`bsh-2.0b6.jar!/bsh/commands/` has 54 entries, extracted with one `unzip -l`) as
  a plugin resource — the same "data, not code" shape `beanshell/maven-scripts.txt` already uses
  for the Maven inline-script list. Suppresses the false positive for every stock command with no
  platform-API work.
- **More thorough, same shape as the existing Java import resolution**: `BshJavaResolver.imports()`
  already walks a file's `IMPORT_DECLARATION`s to build a search list for Java class names —
  `importCommands("path")` is the same idea aimed at commands instead of classes. Enumerating the
  actual classpath roots reachable from the module (`OrderEnumerator`/`ModuleRootManager`, the same
  class of platform API `JavaPsiFacade` already relies on for class resolution) for `bsh/commands/`
  resources, honoring any `importCommands(...)` path a script adds, covers custom or forked
  BeanShell jars too — not just the one pinned version — at the cost of the classpath-scanning code
  the static list does not need.

`importObject(anObject)` is a different mechanism — it imports an *object's* methods into scope at
runtime, not a search path — and stays out of scope for the same reason arbitrary Java library
methods on untyped variables do: resolving it statically would need to know the object's type,
which is exactly the boundary `BshTypeInference` already draws.
