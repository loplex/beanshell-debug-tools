# The BeanShell debug wire protocol

Version **2**. The reference specification: everything an implementation of either end
needs, and the reasoning behind the parts that are not obvious.

Two implementations of the agent end live in this repository — the instrumenting JVM
agent (`agent/hook/…/BshHook.java`) and the source-rewriting fallback
(`plugin/…/debug/agent/BshDebugAgent.java`) — and one of the IDE end
(`plugin/…/debug/BshDebugProcess.kt`, opcodes in `BshDebugProtocol.kt`). They agree on
what is written here; where the two agents differ, it is noted.

- [1. Transport and framing](#1-transport-and-framing)
- [2. Opcodes](#2-opcodes)
- [3. Messages, agent to IDE](#3-messages-agent-to-ide)
- [4. Messages, IDE to agent](#4-messages-ide-to-agent)
- [5. The invariants](#5-the-invariants)
- [6. Handles](#6-handles)
- [7. Configuration (not on the wire)](#7-configuration-not-on-the-wire)
- [8. Failure modes](#8-failure-modes)
- [9. Relationship to DAP](#9-relationship-to-dap)
- [10. Version history](#10-version-history)

---

## 1. Transport and framing

A single **TCP** connection over loopback. The **IDE listens**; the agent connects on
its first report. There is no handshake and no version negotiation — see
[§5](#5-the-invariants) for why.

Encoding is `java.io.DataOutputStream` / `DataInputStream`, so:

| notation | on the wire |
|---|---|
| `byte` | 1 byte |
| `int` | 4 bytes, **big-endian** (`writeInt`) |
| `utf` | `writeUTF`: 2-byte unsigned length, then modified UTF-8 |

Messages are **not length-prefixed**. Each is an opcode byte followed by fields whose
count and types the opcode fixes, so a reader must consume exactly what the opcode
implies — there is no way to skip a message you do not understand, and none is needed
(both ends always ship together).

`writeUTF` caps a string at 65535 bytes. The agent truncates a rendered value to
**20000 chars** before writing it, which cannot exceed the cap even at 3 bytes/char.

## 2. Opcodes

```
IDE -> agent                     agent -> IDE
  0x01  RESUME                     0x10  STOPPED
  0x02  SET_BREAKPOINTS            0x11  SCOPES         answers 0x04
  0x03  SET_RUN_MODE               0x12  VARIABLES      answers 0x05
  0x04  SCOPES                     0x13  EVALUATED      answers 0x06
  0x05  VARIABLES                  0x14  VARIABLE_SET   answers 0x07
  0x06  EVALUATE
  0x07  SET_VARIABLE
```

Request and reply have **separate opcodes** even where the payload shape is identical
(`EVALUATED` vs `VARIABLE_SET`). A reader that can name the reply it expected can tell
a desync from a bad answer.

## 3. Messages, agent to IDE

### `0x10 STOPPED` — a statement is about to run, and the agent is now blocked

```
byte  0x10
int   line          1-based, in the agent's own coordinates (see below)
int   callDepth     CallStack.depth(): +1 per method frame, unaffected by blocks/loops
int   frameCount
frameCount times, innermost first:
  utf name          frame name ("global", a method name, …)
  utf sourceFile    the source the frame's position is in
  int  line         1-based; -1 when the frame has no position (entered from Java)
```

`line` is in **the agent's** coordinates, which are the script's own. For a standalone
`.bsh` file they are the file's lines. For an inline script inside a `pom.xml` they are
relative to the snippet BeanShell was handed, and the IDE maps them — see
[`plugin/docs/DEBUGGING.md`](../plugin/docs/DEBUGGING.md).

**Frame positions are not where you would first look.** `NameSpace.getInvocationLine()`
answers "where was I called *from*", so it describes a position in the next frame out.
Frame *k* is therefore placed at the call site recorded by frame *k-1*, and frame 0 at
the statement being reported. Reading `getInvocationLine()` off each frame directly
yields a stack that is off by one level and looks plausible.

The rewriting agent reports **`frameCount = 1`**. It is handed a `NameSpace` and not a
`CallStack`, so there is no stack to report.

### `0x11 SCOPES` — answers `0x04`

```
byte  0x11
int   count
count times:
  utf name          "Locals", "Global"
  int  handle       never 0
```

`Locals` is the frame's namespace; expanding it walks the parent chain, so it shows
everything the script can see from there. `Global` is the interpreter's own namespace,
present only when it is a *different* object from the frame's (i.e. not at top level)
and only under the instrumenting agent — the rewriting one has no `Interpreter` to ask.

### `0x12 VARIABLES` — answers `0x05`

```
byte  0x12
int   count
count times:
  utf name
  utf value         rendered, truncated to 20000 chars
  utf type          simple type name; "" for null
  int childHandle   0 when there is nothing to expand
```

At most **1000** children are written for one handle. Lazy expansion removes the cost of
unopened objects, not of an opened one, and a million-element list would still stall the
interpreter thread while it serialised.

`bsh.Primitive` — the wrapper around every scripted `int`, `boolean` and so on — is
reported **unwrapped**: `type` is what `Primitive.getType()` says (`int`), and
`childHandle` is 0, since its fields are BeanShell's plumbing rather than the user's
value.

A `bsh.This` is expanded as **the namespace it stands for**, not as a Java object. This
covers three things that look unrelated in the UI: the `_bshThis…` field on an instance
of a scripted class, the namespace a closure captured, and a `This` handed back to Java.

### `0x13 EVALUATED` — answers `0x06`

```
byte  0x13
byte  ok            1 = value, 0 = failed
utf   value         the result, or the reason when ok = 0
utf   type
int   childHandle
```

### `0x14 VARIABLE_SET` — answers `0x07`

Identical shape to `0x13`; `value`/`type` describe the value **after** assignment.

## 4. Messages, IDE to agent

### `0x01 RESUME`

```
byte  0x01
```

Releases the reported statement. The agent drops its handle table here
([§6](#6-handles)).

### `0x02 SET_BREAKPOINTS`

```
byte  0x02
int   count
count times:
  utf file          matched as a suffix of the agent's sourceFile
  int line
```

An optimisation, not a requirement: until the IDE sends this at least once, the agent
reports **every** statement and the IDE decides. Once sent, the agent falls silent while
running and speaks up only where a breakpoint matches — which is what makes a loop
usable.

Only valid where the IDE's line mapping is the **identity**. Filtering needs breakpoints
expressed in the lines the agent reports, and the pom mapping has no inverse, so the
injected-pom path never sends this and takes the round-trip per statement instead.

### `0x03 SET_RUN_MODE`

```
byte  0x03
byte  mode          0 = running, 1 = stepping
```

While stepping, the agent must report every statement, because the IDE owns the
step decision (`BshStepLogic` compares call depths). Stepping is interactive, so a
round-trip is invisible; running is what needed fixing.

### `0x04 SCOPES` / `0x05 VARIABLES` / `0x06 EVALUATE` / `0x07 SET_VARIABLE`

```
byte  0x04   int frameId
byte  0x05   int handle
byte  0x06   int frameId, utf expression
byte  0x07   int frameId, int handle, utf name, utf expression
```

All four are **only valid while the agent is suspended**, since that is when the state
exists and when the agent is reading.

`SET_VARIABLE` carries a frame *as well as* the container, because the new value is an
expression that has to be evaluated somewhere: `h.count = other + 1` needs the frame's
scope even when the container is a plain object.

Assignment to a variable in scope is served by evaluating `name = (expression)` rather
than by calling `NameSpace.setVariable`, so BeanShell applies its own rules — a typed
variable refuses an incompatible value exactly as the script would, and a variable
inherited from an enclosing scope is updated where it was declared instead of being
shadowed. Anything that is not a namespace (a field, an array element, a list slot, a
map entry) has no expression that names it and is reached reflectively.

Neither `EVALUATE` nor `SET_VARIABLE` is available on the **rewriting** agent: it is
handed a `NameSpace`, and a namespace cannot evaluate. It answers `ok = 0` with that
reason rather than letting an opcode it does not serve fall through to "resume", which
would silently continue the script.

## 5. The invariants

These are what an implementation can rely on, and what it must preserve.

1. **A reply is always the next thing on the wire.** Requests are served on the
   interpreter thread, from inside the same loop that waits for `RESUME` — the thread is
   parked there anyway, it owns the state being inspected, and answering from anywhere
   else would need a lock BeanShell does not offer. So replies arrive in request order
   and **no request ids are needed**.

   The IDE side depends on this: one in-flight request, a capacity-1 queue, guarded by a
   lock. A **timeout breaks it** — a late reply would be handed to the *next* request as
   its own — so a timed-out channel is written off permanently rather than reused.

2. **The first statement is always reported**, because the agent opens the connection on
   its first report and cannot know the breakpoints sooner.

3. **One thread.** The protocol has no thread id, and a second suspended script thread
   cannot be told apart or resumed independently. See
   [`FUTURE_WORK.md`](FUTURE_WORK.md#threads); this is what version 3 is reserved for.

4. **No version negotiation**, because there is nothing to negotiate with: the agent jar
   ships inside the plugin, so both ends are always the same build. An independently
   published agent would change this, and is the other reason version 3 exists.

## 6. Handles

A handle is an opaque non-zero `int` naming a value the IDE may expand. `0` is never
issued, so it doubles as "this value has nothing to expand" in `VARIABLES`.

Handles are **identity-based** (expanding the same object twice returns the same handle)
and **valid only until the next `RESUME`**, where the table is dropped. That is what
makes them safe rather than merely compact: the IDE can never hold a reference into a
script that has moved on, so there is no stale-object problem to solve and no cleanup
protocol to get wrong.

The counter keeps rising across resumes rather than restarting, so a reply that crosses
a resume cannot be mistaken for a live handle.

## 7. Configuration (not on the wire)

The agent is configured by **system properties**, not by the protocol, because the hook
is loaded by the bootstrap classloader and system properties are the one channel that is
loader-independent.

| property | meaning |
|---|---|
| `bsh.debug.port` | the IDE's listening port. Absent = not debugging, run untouched |
| `bsh.debug.sources` | comma-separated file-name suffixes to report on |
| `bsh.debug.sources.file` | path to a file of source-name **prefixes**, one per line |
| `bsh.debug.trace` | report to stderr instead of the socket (development aid) |

The two source filters are ORed; neither set means report everything. A filter is not
optional under the instrumenting agent: it also reaches BeanShell's own commands, which
are `.bsh` files inside the jar, so without one the session stops inside `print.bsh` on
every `print()`. The prefix form exists for a script handed over as a *string*, which has
no file name — see [`agent/README.md`](../agent/README.md#which-sources-to-report-on).

## 8. Failure modes

| situation | agent behaviour |
|---|---|
| no `bsh.debug.port` | disables itself; the script runs untouched |
| port set, nothing listening | `System.exit(69)` (`EX_UNAVAILABLE`) — silently skipping every breakpoint is the failure that looks like "it just ran" |
| session drops mid-run | warn and detach; the script continues. Aborting what may be a real Maven build would be worse |
| a request fails (bad expression) | ordinary reply with `ok = 0`; the connection stays up |
| an unreadable object | send whatever was gathered; not worth failing the session |
| reflection setup fails | give up reporting, permanently, rather than throwing from inside a transformer |

## 9. Relationship to DAP

This protocol deliberately uses [DAP][dap]'s **vocabulary and model** under different
names: `STOPPED` is the `stopped` event with the stack inline, `SCOPES`/`VARIABLES` are
`scopes`/`variables` with `variablesReference` renamed to "handle", and `EVALUATE` /
`SET_VARIABLE` are `evaluate` / `setVariable`. Lazy expansion by opaque reference is
DAP's design, not a coincidence.

That is what would make adopting DAP a change of **serialisation** rather than of
design. The costs and the current blockers are in
[`FUTURE_WORK.md`](FUTURE_WORK.md#dap-as-the-transport).

## 10. Version history

**1** — untagged agent-to-IDE stream of statement reports. No return channel beyond
"resume", so every variable had to be pushed on every step.

**2** — both directions opcode-tagged; lazy variable handles; `evaluate` and
`setVariable`. Current.

**3** (reserved) — a thread id on `STOPPED` and on every request, plus request ids,
which invariant 1 currently stands in for. Needed by threads and by an independently
published agent.

[dap]: https://microsoft.github.io/debug-adapter-protocol/specification
