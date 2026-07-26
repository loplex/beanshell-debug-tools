# Future work

Ideas, known limitations and unfinished work worth revisiting, across the whole
repository. Not scheduled — a parking lot so they are not lost to conversation
history.

Ordered by what blocks what, not by size. **Nothing here blocks a release** — the
agent ships inside the plugin and the fixtures are in the repository.

---

## `evaluate` and `setVariable`

The remaining two verbs of DAP's variable vocabulary. Protocol 2 has the shape for
them — the IDE already sends requests and reads replies while suspended, so each is
one opcode and one reply, not a reframing.

`evaluate(frameId, expression)` is what the Watches panel and the Evaluate dialog
need. It is reachable: the hook holds the `Interpreter`, so
`interpreter.eval(expr, namespace)` works, verified. The result gets a handle like
any other value, so a watched expression expands the same way a variable does. Two
things to get right — the re-entrancy guard already covers evaluation running
instrumented code, and a user expression that throws must be reported as a failed
evaluation rather than disabling the session.

`setVariable(handle, name, expression)` is the same machinery in reverse, plus
`XValueModifier` on the IDE side.

Neither is available on the rewriting fallback: it holds no `Interpreter`.

## Threads

The protocol carries no thread id and the hook holds a global lock while suspended,
so two script threads cannot be told apart or suspended independently. Needs a
protocol change, so it belongs with DAP. Exercised by
`agent/samples/scripts/06_callbacks_threads.bsh` and `DebugHost` scenario 5.

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

Having adopted the vocabulary first — protocol 2 is `stackTrace`/`scopes`/
`variables` with lazy handles under different names — is what makes this a
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
