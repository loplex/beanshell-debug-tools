# Future work

Ideas, known limitations and unfinished work worth revisiting, across the whole
repository. Not scheduled — a parking lot so they are not lost to conversation
history.

Ordered by what blocks what, not by size. **Nothing here blocks a release** — the
agent ships inside the plugin and the fixtures are in the repository.

---

## Threads

Two script threads cannot be told apart or suspended independently. Worth being
precise about the size of this, because the protocol change is the *small* part and
reads like the whole job.

Exercised by `agent/samples/scripts/06_callbacks_threads.bsh` and `DebugHost`
scenario 5.

**Protocol.** A thread id on `STOPPED` and on every request, plus request ids —
today a reply is matched by arrival order, which is sound only while one request is
in flight (see the desync note in `BshDebugProcess.exchange`). That is a version 3,
but it is mechanical.

**The hook is the real work.** Its suspended state is all static: `LOCK`,
`currentFrames`, `currentInterpreter`, `handles`, `nextHandle`. Making those
per-thread is straightforward; the structural change is that **the hook has no
thread of its own today**. A suspended thread reads its own commands off the socket,
which cannot work once two are suspended — only one can be reading. It needs a
dedicated reader that demultiplexes into per-thread mailboxes, and `runMode` and the
step depth become per-thread with it. That new thread lives on the bootstrap
classpath inside somebody else's JVM, so getting its lifecycle wrong hangs a real
Maven build rather than a test.

**IDE side.** `mode`, `stepDepth` and `currentDepth` become per-thread;
`exchange` becomes a map of pending requests keyed by id; `XSuspendContext` gains
`getExecutionStacks()` with one stack per thread. And a suspend policy has to be
decided — whether a breakpoint stops the thread that hit it or all of them — which
both JDWP and DAP make a per-breakpoint choice, so it is UI as well as plumbing.

**Does DAP have to come first?** No — and the earlier note here overstated it. The
work splits cleanly:

- The **hook** work (a reader thread, per-thread state, per-thread mailboxes) is
  transport-independent. It is the expensive part, and DAP would not change a line of
  it. A DAP-speaking agent needs it just as much.
- Only the **presentation** is written twice, and only if the plugin later moves onto
  a DAP client: `XSuspendContext.getExecutionStacks()` today, a `Thread` event when
  LSP4IJ grows one. That is the small half.

So threads can be done for IntelliJ now, and are worth doing on their own terms. What
does *not* work is the reverse order — routing IntelliJ through DAP first would lose
threads on the way, since [LSP4IJ] does not implement the `Thread` event.

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

## DAP as the transport

Worth doing if VS Code support becomes valuable, or if maintaining an IntelliJ
plugin stops being. Note what it costs on the IntelliJ side: there is **no native
DAP** ([IJPL-83441] is open), and [LSP4IJ] supplies a general DAP client that lacks
`Pause`, `ExceptionInfo`, `SetFunctionBreakpoints` and the `Thread` event — that
last gap being exactly the threads item above.

Having adopted the vocabulary first — protocol 2 is `stackTrace`/`scopes`/
`variables`/`evaluate`/`setVariable` with lazy handles, under different names — is
what makes this a transport swap rather than a redesign. A DAP-speaking agent is a
debug adapter any DAP client can attach to, which is why the agent stays an
independently publishable subproject rather than a source set of the plugin.

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
