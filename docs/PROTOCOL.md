# The BeanShell debug wire protocol

Version **3** of the native protocol. **DAP is also available** as a second transport —
`-Dbsh.debug.protocol=dap` — see [§9](#9-relationship-to-dap). This document specifies the
native one, which is what the IntelliJ plugin speaks and remains the default.

The reference specification: everything an implementation of either end
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
  0x08  SET_CATCH_ALL
  0x07  SET_VARIABLE
```

**Everything is addressed to a thread**, except `SET_BREAKPOINTS` — the breakpoint set is
shared. An IDE-to-agent message carries `threadId` immediately after the opcode; the four
that expect an answer then carry a `requestId` which the reply echoes back.

Request and reply have **separate opcodes** even where the payload shape is identical
(`EVALUATED` vs `VARIABLE_SET`). A reader that can name the reply it expected can tell
a desync from a bad answer.

## 3. Messages, agent to IDE

### `0x10 STOPPED` — a statement is about to run, and the agent is now blocked

```
byte  0x10
int   threadId      the agent's own small id, stable for the thread's life; >= 1
utf   threadName    the JVM thread's name ("main", "bsh-X"), for the Threads combo
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
int   requestId     echoes the request
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
int   requestId     echoes the request
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
int   requestId     echoes the request
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
int   threadId
```

Releases that thread's reported statement, and only that thread's. The agent drops that
thread's handle table here ([§6](#6-handles)); another suspended thread keeps its own.

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

### `0x08 SET_CATCH_ALL`

```
byte  0x08
byte  on            1 = every thread reports its next statement, 0 = back to normal filtering
```

Global, like `SET_BREAKPOINTS`, and how **Suspend: All** is honoured. The IDE sets it when a
breakpoint whose policy is `ALL` is hit, and clears it on resume.

There is no way to freeze a thread from outside — it only ever stops where it calls the
hook — so "suspend all" is built the other way round: every other thread reports its *next*
statement even though no breakpoint sits there, and the IDE holds each one as it arrives. Two
consequences worth stating plainly:

- **It is not instantaneous.** A thread sleeping, blocked, or deep in Java code reports
  nothing until it next reaches a script statement, and keeps running until then. "Everyone
  stops at their next statement" is the real guarantee; "everyone stops now" is not on offer.
- **While set, every statement on every running thread is a round-trip.** Acceptable only
  because it lasts exactly as long as somebody is looking at a stopped thread — hence the
  IDE clearing it on resume rather than leaving it on.

The rewriting agent reads and ignores it: it holds one lock per stop, so the other threads are
already waiting inside the hook and there is nothing to round up.

### `0x03 SET_RUN_MODE`

```
byte  0x03
int   threadId
byte  mode          0 = running, 1 = stepping
```

Per thread, because stepping one thread must not make the others report every statement as
well. While stepping, the agent must report every statement on that thread, because the IDE
owns the step decision (`BshStepLogic` compares call depths). Stepping is interactive, so a
round-trip is invisible; running is what needed fixing.

### `0x04 SCOPES` / `0x05 VARIABLES` / `0x06 EVALUATE` / `0x07 SET_VARIABLE`

```
byte  0x04   int threadId, int requestId, int frameId
byte  0x05   int threadId, int requestId, int handle
byte  0x06   int threadId, int requestId, int frameId, utf expression
byte  0x07   int threadId, int requestId, int frameId, int handle, utf name, utf expression
```

All four are **only valid while the named thread is suspended**, since that is when its
state exists. They are served *on that thread*, from the loop where it waits to be resumed:
only it can safely touch its own BeanShell state.

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

1. **A reply is matched by its request id, never by arrival order.** This was the opposite in
   version 2, where requests were served from the suspended thread's own loop and a reply was
   always the next thing on the wire. With two threads suspendable, thread B may report a stop —
   or have its own request answered — between A's request and A's answer, so both ends
   demultiplex: the agent by thread, the IDE by request id.

   The old shape also made a **timeout unsafe**: a late reply would be collected by the next
   request as its own answer, which is why a timed-out channel had to be written off for good.
   With ids, a late reply simply finds no waiter and is dropped.

2. **By default only the thread that hit a breakpoint is suspended.** Others keep running, and
   may report while it is parked. A breakpoint whose policy is Suspend: All rounds them up via
   [`SET_CATCH_ALL`](#0x08-set_catch_all) — approximately, and the approximation is inherent: a
   thread is only ever stopped where it calls the hook, so one inside Java code cannot be frozen.
   "Everyone stops at their next statement" is what the protocol can promise.

3. **Requests are served on the thread they concern**, from inside the loop where it waits to be
   resumed. Not a shortcut: that thread owns the BeanShell state being inspected, and answering
   from anywhere else would need a lock BeanShell does not offer. The agent's reader thread only
   ever *routes* a request into that thread's mailbox.

4. **The first statement is always reported**, because the agent opens the connection on its
   first report and cannot know the breakpoints sooner.

5. **Thread ids are the agent's own**, small and starting at 1, not `Thread.getId()`. Stable for
   as long as the thread lives. An id the agent no longer knows (the thread finished) makes a
   request a no-op rather than an error.

6. **No version negotiation**, because there is nothing to negotiate with: the agent jar ships
   inside the plugin, so both ends are always the same build. An independently published agent
   would change this — see [`FUTURE_WORK.md`](FUTURE_WORK.md#dap-as-the-transport).

### What the rewriting agent does differently

It reports its thread id and echoes request ids like the instrumenting one, so the IDE needs no
special case. But it **keeps one lock for the whole of a stop**, so a second script thread waits
rather than reporting alongside. That is deliberate: this path exists to need nothing — no agent,
no JVM flag, no bootstrap classloader — and independent suspension needs a reader thread of its
own. It also reports `frameCount = 1` always, having been handed a `NameSpace` rather than a
`CallStack`.

## 6. Handles

A handle is an opaque non-zero `int` naming a value the IDE may expand. `0` is never
issued, so it doubles as "this value has nothing to expand" in `VARIABLES`.

Handles are **identity-based** (expanding the same object twice returns the same handle)
and **valid only until that thread's next `RESUME`**, where its table is dropped. The tables
are per thread but the ids are allocated globally, so a handle can only ever mean one object:
a request naming the wrong thread finds nothing rather than silently expanding a different
thread's value that happened to share a number. That is what
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

Two transports, chosen at premain by `bsh.debug.protocol`:

| value | transport | direction | who uses it |
|---|---|---|---|
| `native` (default) | this document | agent **connects** to the IDE's port (`bsh.debug.port`) | the IntelliJ plugin |
| `dap` | [Debug Adapter Protocol][dap] | agent **listens** (`bsh.debug.listen`, defaults to `bsh.debug.port`) | VS Code, Neovim, Eclipse, … |

**Why both, rather than DAP replacing this.** LSP4IJ's DAP client does not implement the
`thread` event, so routing IntelliJ through DAP would *lose* the thread support the native
path has. Keeping both is the arrangement where neither side pays for the other's
limitations — and the cost is small, because only the last step of the hook turns an answer
into bytes.

The directions differ for a reason rather than by accident. The native channel connects out
because the IDE launches the process and already chose a port. A DAP client instead expects
to **attach** to something running, so under DAP the agent listens — and the script blocks on
its first statement until the client has sent `configurationDone`, without which a short
script would finish before a breakpoint could be set.

**What the split looks like in code.** `DebugChannel` is the interface; `NativeChannel` and
`DapChannel` implement it. Everything above it — deciding what counts as a statement, walking
the call stack, rendering values, handing out handles, evaluating in a frame's namespace — is
written once. That was possible because this protocol was built in DAP's vocabulary to begin
with: `STOPPED`/`SCOPES`/`VARIABLES` are `stopped`/`scopes`/`variables`, and a handle *is* a
`variablesReference`.

**Three things DAP needs that this protocol does not**, all handled inside `DapChannel`:

- **Globally unique frame ids.** The hook numbers frames per thread; DAP's `stackTrace` hands
  out ids that `scopes` later quotes without naming a thread. Encoded as
  `threadId * 1000 + frameIndex`.
- **Stepping as a request.** DAP's `next`/`stepIn`/`stepOut` mean "set the mode *and* go",
  where the native protocol keeps the two apart. One DAP request becomes two commands.
- **A handshake.** `initialize` → `initialized` → `setBreakpoints` → `configurationDone`,
  which this protocol deliberately has none of.

**What the DAP side does not implement**, and says so in its capabilities rather than claiming
it: conditional breakpoints, function breakpoints, exception breakpoints, step-back,
restart-frame, and `pause`. The last is not an omission but the same limit as
[invariant 2](#5-the-invariants) — a thread can only be stopped where it calls the hook, so
there is nothing to interrupt. It answers `pause` with that reason instead of accepting and
doing nothing.

Exercised by `agent/checks/07-dap-transport.sh`, with `agent/checks/dap-client.py` as a
standalone client for driving a session by hand.

## 10. Version history

**1** — untagged agent-to-IDE stream of statement reports. No return channel beyond
"resume", so every variable had to be pushed on every step.

**2** — both directions opcode-tagged; lazy variable handles; `evaluate` and
`setVariable`. Current.

**3** — a thread id on `STOPPED` and on every request, plus request ids on the four that
expect a reply. Threads are suspended and resumed independently, each with its own frames,
handle table and run mode. Current.

**4** (nothing reserved yet) — the likely contents are a `threadExited` event and a reason on
`STOPPED` (breakpoint / step / round-up), which the DAP channel currently reports generically.
A capabilities handshake is no longer among them: DAP has one, and the native protocol still has
both ends shipping together.

[dap]: https://microsoft.github.io/debug-adapter-protocol/specification
