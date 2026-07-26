# Future work

Ideas, known limitations and unfinished work worth revisiting, across the whole
repository. Not scheduled — a parking lot so they are not lost to conversation
history.

Ordered by what blocks what, not by size.

---

## Blocking a release

### 1. Bundle the agent jar with the plugin

`BshDebugAgentJar.locate()` searches an override property, then next to the plugin
jar, then the sibling subproject's `build/libs/` as a development fallback. Only
the second belongs in a shipped plugin, and **nothing populates it yet**, so a
released plugin silently falls back to source rewriting.

The mechanism already exists in `plugin/build.gradle.kts` — `mavenExtJar` is fed
into `processResources` exactly this way — so this is wiring
`:agent:instrument:shadowJar` in beside it, not a new problem.

### 2. Move the debugger fixtures into this repository

`debug-samples/` (9 scripts + `DebugHost.java` + a README with a hook-point
coverage table) lives in the BeanShell checkout at
`~/SOURCE-git/beanshell/debug-samples`, in a `TMP debug samples` commit. They are
debugger fixtures, not BeanShell tests. Once moved, revert that commit there.

They cover interpreter hook coverage, closures, threads, scripted classes and an
embedded-host driver — everything `plugin/samples/` does not.

---

## The values problem — an object-handle model in DAP vocabulary

This is the user-visible one, and it is where the next protocol iteration starts.

Each variable is reported as a single `toString()` string (`readVariables` →
`writeUTF`). Simple and robust — no dangling references to objects living in the
foreign JVM — but limited three ways:

- **No structure.** Nested objects and collections cannot be expanded in the
  Variables panel; the developer sees one flat line per variable.
- **Hard size cap.** `DataOutputStream.writeUTF` tops out at 65535 bytes, so values
  are truncated (`MAX_VALUE_LENGTH`) and large objects lose their tail.
- **Eager, per-step.** Every value is serialized on every step, even when the
  developer never looks at it.

The fix is not more plumbing but an object-handle model: send lightweight handles
and let the IDE request a specific object's members on demand. **That maps 1:1 onto
DAP's `variablesReference`**, so adopt DAP's vocabulary — `stackTrace` → `scopes` →
`variables` with lazy children, `setVariable`, `evaluate` — even before adopting
DAP's transport, and the design is done once.

Evaluation is already reachable: the hook holds the `Interpreter`, so
`interpreter.eval(expr, namespace)` works. Verified.

Touches both the agent and the IDE side (`BshDebugProcess`, `BshDebugFrames`), and
it is a protocol change — a deliberate iteration rather than a tweak.

## Multi-frame stack

Deliberately deferred to land with the DAP vocabulary above. The agent already
receives the `CallStack`, so `toArray()` plus `getInvocationLine()` per frame gives
the whole stack.

Worth noting this was impossible under the old `step(line, namespace)` signature at
all, since a `NameSpace` does not know its caller — `Name.java:82-84`: *"This
references do not really know anything about their depth on the call stack"*.
`.caller` works by counting literal occurrences and indexing the `CallStack`, which
is why `c = this.caller; c.caller` is rejected.

## Threads

The protocol carries no thread id and the hook holds a global lock while suspended,
so two script threads cannot be told apart or suspended independently. Needs a
protocol change, so it belongs with DAP. Exercised by
`debug-samples/06_callbacks_threads.bsh` and `DebugHost` scenario 5 — see item 2.

---

## Turn `BshInstrumentationMode.CURRENT` into a real setting

It is a compile-time constant today. The rewriting fallback is genuinely useful
(it needs no agent jar and no JVM flag), so choosing it should not require a build.

## DAP as the transport

Worth doing if VS Code support becomes valuable, or if maintaining an IntelliJ
plugin stops being. Note what it costs on the IntelliJ side: there is **no native
DAP** ([IJPL-83441] is open), and [LSP4IJ] supplies a general DAP client that lacks
`Pause`, `ExceptionInfo`, `SetFunctionBreakpoints` and the `Thread` event — that
last gap being exactly the threads item above.

Adopting the vocabulary first (see the values problem) is what makes this a
transport swap rather than a redesign. A DAP-speaking agent is a debug adapter any
DAP client can attach to, which is why the agent stays an independently publishable
subproject rather than a source set of the plugin.

[IJPL-83441]: https://youtrack.jetbrains.com/issue/IJPL-83441/Debug-adapter-protocol-support
[LSP4IJ]: https://github.com/redhat-developer/lsp4ij

---

## Smaller, independent

### Non-zero exit from `tools/run-debug-bsh.sh` on script errors

`bsh.Interpreter` prints a "Target exception" but still exits `0` when a script
fails to evaluate (the connect-failure case is already handled — the agent calls
`System.exit(69)`). For the command-line tools it would be nicer if a failing
script produced a non-zero exit, so callers/CI can detect it. A wrapper-level
concern (parse the interpreter's output, or run the script via a small launcher
that propagates eval errors), not an agent change.

### (Optional) Second JDWP channel for step-into Java

The original inline-debug plan left one optional item unimplemented: a second,
JDWP-based debug channel (reuse `BshJavaDebugAttach`) so the developer can step
*into* the Java code a Maven-run script calls, in addition to line-stepping the
script itself. Independent of the script-level transport; purely additive.
