# BeanShell debugging in Eclipse

Eclipse has no built-in DAP client of its own; the generic one is
[LSP4E](https://github.com/eclipse-lsp4e/lsp4e)'s **Debug Adapter** launch configuration
(`org.eclipse.lsp4e.debug`) — the Eclipse counterpart to LSP4IJ, which the IntelliJ plugin's
own [`docs/FUTURE_WORK.md`](../../docs/FUTURE_WORK.md) already names as the reason the native
protocol and DAP stay separate transports. Same [debug agent](../../agent/README.md), same
[DAP transport](../../docs/PROTOCOL.md#9-relationship-to-dap) as
[`../vscode/`](../vscode/README.md) and [`../neovim/`](../neovim/README.md) — this is packaging
for a third editor, not a third implementation.

**Attach only.** LSP4E's generic launcher can either start a Debug Adapter Server itself or
connect to one already running, but starting one means spawning something that speaks DAP —
and this agent is not a standalone adapter: it only starts listening once it is loaded into the
JVM you want to debug, via `-javaagent`. Unlike the VS Code extension or the Neovim config,
nothing here can drive that JVM launch itself, since doing so from a generic Eclipse launcher
is not scriptable the way `child_process.spawn`/`jobstart` are. Start the target JVM yourself
and attach.

## 1. Start the target JVM

```bash
java -javaagent:/path/to/bsh-debug-agent-1.0.0-SNAPSHOT.jar \
     -Dbsh.debug.protocol=dap -Dbsh.debug.listen=4711 \
     -Dbsh.debug.sources=script.bsh \
     -cp /path/to/bsh-2.0b6.jar bsh.Interpreter script.bsh
```

It blocks on the first statement — `[bsh-agent] DAP: listening on 127.0.0.1:4711, waiting for a
client to attach` on stderr — until the client below has finished configuring.

## 2. Create the launch configuration

Install LSP4E's debug support if it isn't already part of your Eclipse install, then:
**Run → Debug Configurations… → Debug Adapter → New**. Point the connection at the JVM above
(`127.0.0.1`, the port `bsh.debug.listen` was set to) rather than having Eclipse start a server,
and set **Launch parameters as Json** to:

```json
{ "request": "attach" }
```

LSP4E defaults every session to a `launch` request; `"request": "attach"` is what tells it the
target is already running rather than something for it to start. Enabling **"monitor Debug
Adapter launcher process"** is worth turning on the first time, to see the raw DAP traffic while
confirming the setup — see
[LSP4E's own doc](https://github.com/eclipse-lsp4e/lsp4e/blob/main/documentation/using-built-in-debug-adapter-launch-configuration.md)
for the exact fields the launcher dialog exposes in your version.

## What isn't supported

Same limits as the agent everywhere else, declared in its DAP capabilities rather than silently
ignored: no pause (a BeanShell thread only stops where it calls the hook, so there is nothing to
interrupt), no conditional/function/exception breakpoints, no step-back, no restart-frame.

## Manual verification runbook

Unlike [`../vscode/`](../vscode/README.md#testing) and [`../neovim/`](../neovim/README.md#testing),
there is no automated end-to-end test here. Both of those cover code this repository owns — the
extension's own JVM launch, `bsh-dap.lua`'s own launch — that a hand-rolled DAP client can't
exercise. There is no equivalent here: this package is a README, not a launcher, and LSP4E's
generic Debug Adapter launch configuration (configured entirely through its own UI dialog) is
upstream code this repository doesn't own. Automating it would mean standing up a second build
toolchain (Tycho, a p2 target platform, SWTBot) to re-verify that *LSP4E* speaks DAP correctly
against this agent — already proven, against the same agent, by
[`agent/checks/07-dap-transport.sh`](../../agent/checks/07-dap-transport.sh)'s `dap-client.py`.

What's worth checking by hand — after touching the agent, the DAP transport, or this doc — is
that LSP4E's *own* attach flow still holds up end to end, using
[`samples/script.bsh`](samples/script.bsh) (the same fixture, breakpoint line and evaluate
expression `07` and the other two editors' tests already prove work):

1. Resolve the agent jar and classpath: `./gradlew -q :agent:samples:printPaths` from the
   repository root, giving `AGENT_JAR` and `BSH_CLASSPATH`.
2. Start the target JVM (see [step 1](#1-start-the-target-jvm) above), pointed at
   `samples/script.bsh`, and confirm it blocks on `DAP: listening on 127.0.0.1:4711, waiting for
   a client to attach` before doing anything else.
3. In Eclipse, open `samples/script.bsh` and set a line breakpoint on
   `return doubled + total;` (line 6).
4. Create or reuse the **Debug Adapter** launch configuration (see
   [step 2](#2-create-the-launch-configuration) above) and launch it.
5. Confirm, in that order:
   - execution stops inside `compute()`, with the caller frame (`compute(7)` in `global`) also
     visible in the call stack — not just the innermost frame;
   - the Variables view offers both a **Locals** scope (`n = 7`, then `doubled = 14` once past
     the assignment) and a **Global** scope (`total = 5`);
   - evaluating `doubled + 1` (Display/Expressions view) returns `15`;
   - resuming lets the script run to completion (`script done` on the target JVM's stdout) —
     and, since `DapChannel` never sends a `terminated`/`exited` DAP event, watch what LSP4E
     itself does when the socket merely drops: whether the Debug view marks the session
     terminated on its own or is left stuck, since that is LSP4E's behaviour to characterize, not
     this agent's to fix.

A step that stops holding is a regression in the DAP transport itself — cross-check against `07`
and the VS Code/Neovim tests before assuming it's LSP4E's own behaviour that changed.
