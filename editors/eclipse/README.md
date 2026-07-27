# BeanShell debugging in Eclipse

Eclipse has no built-in DAP client of its own; the generic one is
[LSP4E](https://github.com/eclipse-lsp4e/lsp4e)'s **Debug Adapter** launch configuration
(`org.eclipse.lsp4e.debug`) — the Eclipse counterpart to LSP4IJ, which the IntelliJ plugin's
own [`docs/FUTURE_WORK.md`](../../docs/FUTURE_WORK.md) already names as the reason the native
protocol and DAP stay separate transports. Same [debug agent](../../agent/README.md), same
[DAP transport](../../docs/PROTOCOL.md#9-relationship-to-dap) as
[`../vscode/`](../vscode/) and [`../neovim/`](../neovim/) — this is packaging for a third
editor, not a third implementation.

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
