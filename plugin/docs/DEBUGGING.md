# Debugging internals

BeanShell is an interpreter with no debug protocol: a running script is evaluated
as an AST inside `bsh.Interpreter`, so the JVM has no bytecode/line information for
`.bsh` lines and JDWP breakpoints cannot bind to them. The plugin therefore
implements a **source-level debugger by instrumentation**, and layers Java (JDWP)
debugging on top for the Java code a script calls.

The measurements behind "JDWP cannot bind to them" — and why that is a property of
the language rather than a gap someone could close — are in
[`agent/README.md`](../../agent/README.md).

## Which instrumentation runs

There are two runtime mechanisms and one verification oracle. The mechanism is a
per-configuration setting — *Debug instrumentation* in the BeanShell run
configuration — defaulting to `AGENT`.

| Implementation | Role |
|---|---|
| `agent/` (JVM agent) | **Default.** Instruments the interpreter; the script is untouched |
| `debug/BshDebugInstrumenter.kt` (PSI) | Fallback. Needs only a source file — no agent, no JVM flag |
| `tools/bshInstrumenter.main.kts` | **Not a runtime option.** A verification oracle |

The setting is stored by enum **name** rather than ordinal, so reordering
`BshInstrumentationMode` cannot silently repoint saved configurations at the other
mechanism, and an unrecognised value falls back to the default rather than refusing
to launch. Choosing `AGENT` with no agent jar available also falls back to rewriting:
the user asked to debug, and a degraded session beats none. The Maven inline-script
path always rewrites — there is no run configuration of its own to ask.

The agent is preferred because rewriting is visible to the user: the injected call
becomes a real statement, so it shows up in `getInvocationText()` and therefore in
error messages and stack traces. Rewriting also cannot reach scripts the plugin
never sees — `eval(String)` input, scripts loaded as classpath resources, or script
text built at runtime — which is the common shape for a library that embeds
BeanShell. The fallback survives because it needs nothing but the file: no agent
jar, no `-javaagent`.

The `.kts` was never a competing production path. PSI classes do not exist outside
a running IntelliJ, so it reproduces rewriting on the command line. What it retains
is unique value: it decides instrumentability **empirically**, by inserting the
hook, reparsing with BeanShell's *own* parser, and accepting only when the tree is
the original with one statement spliced in as a sibling. That makes it
authoritative about what is a statement, so it is kept as the oracle in
`tools/check-instrumentation.py` rather than maintained as a third way to run a
session. Its `O(n²)` reparsing would rule it out for interactive use anyway.

**Two different things are called "the agent".** `agent/` is the ASM-instrumenting
JVM agent (`cz.loplex.bsh.*`), the default. `debug/agent/BshDebugAgent.java` is the
in-plugin hook class the *rewritten source* calls into
(`cz.loplex.intellij.bsh.debug.agent`). Which one is meant follows from the package.

## The agent path (default)

`BshDebugAgentJar.locate()` finds the jar, the forked JVM is started with
`-javaagent:…`, and `bsh.Interpreter`'s AST evaluation is instrumented in memory.
The script on disk is never modified. Internals — the transformer, the bootstrap
hook, the landmines — are in [`agent/README.md`](../../agent/README.md); the wire
protocol is documented there too.

## The rewriting path (fallback)

### 1. Instrumentation — `debug/BshDebugInstrumenter.kt`
Before launching, the script is rewritten to call a hidden hook in front of every
statement:

```
cz.loplex.intellij.bsh.debug.agent.BshDebugAgent.step(<line>, this.namespace);
```

Hooks are inserted **only** at statement boundaries (direct children of the file
root and of `{ }` blocks), never inside an expression or a brace-less body, so the
transformation is semantics-preserving — each hook is just one extra statement.
Reported line numbers are the originals, so breakpoints keep mapping after the
preamble/hooks shift the text.

### 2. The in-plugin hook — `debug/agent/BshDebugAgent.java`
A pure-JDK class (no Kotlin/IntelliJ/BeanShell compile dependency) added to the
forked JVM's classpath. On the first `step()` it connects to the IDE over a
socket (`bsh.debug.port`). For each statement it sends `line`, the call depth, and
the caller's variables (read reflectively from the passed `bsh.NameSpace`), then
**blocks** until the IDE releases it. The script's own stdout/stderr stay clean.

## The IDE side — `debug/BshDebugProcess.kt`

Shared by both paths, because both speak the same protocol. An `XDebugProcess`
reads the frames and decides, per statement, whether to pause (a breakpoint on that
line, or the active step mode) or release immediately. `BshLineBreakpointType`
allows breakpoints in `.bsh` files; `BshDebugFrames` exposes the stack frame and
variables.

### Stepping by call depth — `debug/BshStepLogic.kt`
The reported BeanShell call depth is monotonic per nested call. Step actions
compare against the depth captured when the step began:

| Action | Pauses when |
|--------|-------------|
| Step Into | next statement, any depth |
| Step Over | depth ≤ the step-point depth (skips descents into calls) |
| Step Out | depth < the step-point depth (only after returning) |
| Resume | only at a breakpoint |

## Java breakpoints (dual session) — `debug/BshDebugRunner.kt`

When the Java plugin is present, the forked JVM is also started under **JDWP**
(`-agentlib:jdwp=…,server=y,suspend=y`) and `BshJavaDebugAttach` attaches
IntelliJ's Java debugger to it (via the standard *Remote JVM Debug*
configuration, looked up by id — no compile dependency on the Java plugin).
`suspend=y` guarantees the JVM waits for the debugger, so breakpoints in Java code
are armed before any script code runs.

The result is two debug sessions for one run: a **BeanShell** session (line
breakpoints, script variables) and a **Java** session (breakpoints in the Java
code the script invokes). Without the Java plugin, JDWP is not added and only the
BeanShell debugger runs.

## Variables and frames

Both are fetched on demand. A stop reports only the stack — one entry per frame,
innermost first — and the IDE asks for a frame's scopes when the user selects it,
then for a value's children when the user expands it. `BshStackFrame` and
`BshValue` in `debug/BshDebugFrames.kt` are thin: the work is the request, and
`BshDebugProcess` implements `BshValueSource` to make it.

Nested objects, collections, maps and arrays therefore expand in the Variables
panel, and a value nobody looks at is never serialised.

**The two mechanisms differ here**, and the protocol lets them say so. The agent is
handed the whole `CallStack`, so it reports every frame. The rewriting fallback is
handed a single `NameSpace`, which does not know its caller, so it reports one
frame and offers no nested expansion — it reads values out of the namespace as
strings and never holds the objects.

## Evaluating, and Set Value

`BshStackFrame.getEvaluator()` backs Watches and the Evaluate dialog;
`BshValue.getModifier()` backs Set Value. Both send the expression to the agent,
which runs it through the real interpreter in the selected frame — so `count + 1`
and `twice(count)` mean there what they mean in the script. The agent side is
described in [`agent/README.md`](../../agent/README.md).

Three things the IDE side is responsible for:

- **Neither is offered where it cannot work.** `BshValueSource.supportsEvaluation`
  is false on the rewriting path, so no evaluator and no modifier are handed to the
  platform — better than offering them and failing. `getModifier()` also returns
  null for a value with no container, such as an expression's own result.
- **Requests run off the UI thread.** The platform calls the evaluator from the UI
  thread for the Evaluate dialog, and the exchange with the agent blocks, so both go
  through `executeOnPooledThread`; the callbacks are designed to be invoked later.
- **An unanswered request retires the channel.** Replies are matched by arrival
  order and carry no request id, which is only sound while every request is
  answered. After a timeout the agent may still be working, and its late reply would
  otherwise be handed to the next request as its own answer — so `BshDebugProcess`
  marks the channel desynced instead. Correlating replies is the general fix and it
  belongs with threads, which need it anyway.

Evaluation has its own, much longer timeout: a watch expression is arbitrary user
code, so a few seconds is a plausible answer rather than a failure.

## Limitations

- The protocol carries no thread id and the hook holds a global lock while
  suspended, so two script threads cannot be told apart or suspended
  independently.
- A scripted class instance shows BeanShell's own `_bshThis…` back-reference among
  its fields, because it is a real field rather than a synthetic one.

Both are tracked in [`docs/FUTURE_WORK.md`](../../docs/FUTURE_WORK.md).
