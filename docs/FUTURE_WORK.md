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

## DAP as the transport

Worth doing if VS Code support becomes valuable, or if maintaining an IntelliJ
plugin stops being. Note what it costs on the IntelliJ side: there is **no native
DAP** ([IJPL-83441] is open), and [LSP4IJ] supplies a general DAP client that lacks
`Pause`, `ExceptionInfo`, `SetFunctionBreakpoints` and the `Thread` event — that
last gap being exactly the threads item above.

Having adopted the vocabulary first — protocol 3 is `stackTrace`/`scopes`/
`variables`/`evaluate`/`setVariable` with lazy handles, under different names — is
what makes this a transport swap rather than a redesign. A DAP-speaking agent is a
debug adapter any DAP client can attach to, which is why the agent stays an
independently publishable subproject rather than a source set of the plugin.

### Two transports rather than a swap

The better shape is probably **both**: the native protocol for IntelliJ, DAP for
everyone else, chosen by a system property at premain alongside `bsh.debug.port`.

It is cheap because the split falls in the right place already. Everything expensive
in the hook — deciding what is a statement, walking the call stack, rendering values,
handing out handles, evaluating in a frame's namespace — produces *answers*, and only
the last step turns an answer into bytes. Today that step is `DataOutputStream`
writes; DAP would make it JSON over `Content-Length` framing. So the work is one
serialisation layer behind an interface, not two debuggers.

Two things genuinely differ and are worth knowing before starting:

- **DAP is request/response with ids, this protocol is strictly alternating.** The
  native side leans on "a reply is always next on the wire" to avoid request ids (see
  [`PROTOCOL.md`](PROTOCOL.md#5-the-invariants)). A DAP layer needs the ids anyway, so
  doing threads first — which also needs them — pays for both.
- **DAP clients expect capabilities negotiation** (an `initialize` handshake), which
  this protocol deliberately has none of. That is additive: the native transport keeps
  answering "there is nothing to negotiate", the DAP one answers honestly about what
  it does not implement.

The reason to want it is unchanged: it is what makes the agent usable from VS Code,
Neovim or Eclipse. The reason not to start with it is that it buys IntelliJ nothing —
see the LSP4IJ gaps above.

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
