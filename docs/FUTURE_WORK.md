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
`setVariable`, handles as `variablesReference`) is what made it a serialisation change rather
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
