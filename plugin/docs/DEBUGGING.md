# Debugging internals

BeanShell is an interpreter with no debug protocol: a running script is evaluated
as an AST inside `bsh.Interpreter`, so the JVM has no bytecode/line information
for `.bsh` lines and JDWP breakpoints cannot bind to them. The plugin therefore
implements a **source-level debugger by instrumentation**, and layers Java (JDWP)
debugging on top for the Java code a script calls.

## How BeanShell-level debugging works

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

### 2. The agent — `debug/agent/BshDebugAgent.java`
A pure-JDK class (no Kotlin/IntelliJ/BeanShell compile dependency) added to the
forked JVM's classpath. On the first `step()` it connects to the IDE over a
socket (`bsh.debug.port`). For each statement it sends `line`, the call depth, and
the caller's variables (read reflectively from the passed `bsh.NameSpace`), then
**blocks** until the IDE releases it. The script's own stdout/stderr stay clean.

### 3. The IDE side — `debug/BshDebugProcess.kt`
An `XDebugProcess` reads the frames and decides, per statement, whether to pause
(a breakpoint on that line, or the active step mode) or release immediately.
`BshLineBreakpointType` allows breakpoints in `.bsh` files; `BshDebugFrames`
exposes the stack frame and variables.

### Stepping by call depth — `debug/BshStepLogic.kt`
The agent reports the BeanShell call depth (the number of active `bsh.BshMethod`
frames on the JVM stack — monotonic per nested call). Step actions compare against
the depth captured when the step began:

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

## Limitations

- Step Over/Into/Out are driven by call depth; recursion is handled, but there is
  a stdin/stdout round-trip per statement (fine for debugging pace).
- Variable values are shown as their `toString()`; there is no expression
  evaluation against the live namespace yet.
- The BeanShell stack view is single-frame (the current statement); the Java
  session provides the full JVM stack when stopped in Java code.
