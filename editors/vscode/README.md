# BeanShell Debug for VS Code

Debugs a `.bsh` script through the same [debug agent](../../agent/README.md) the IntelliJ
plugin uses, over the agent's [DAP transport](../../docs/PROTOCOL.md#9-relationship-to-dap)
(`-Dbsh.debug.protocol=dap`) rather than the native protocol IntelliJ speaks. This extension is
packaging, not protocol: the agent already implements everything below, this just gives it a
`launch.json` shape and a `.bsh` language id instead of a hand-written configuration.

## Two ways to start a session

**Attach** — you already started the target JVM yourself, with the agent listening:

```bash
java -javaagent:/path/to/bsh-debug-agent-1.0.0-SNAPSHOT.jar \
     -Dbsh.debug.protocol=dap -Dbsh.debug.listen=4711 \
     -Dbsh.debug.sources=script.bsh \
     -cp /path/to/bsh-2.0b6.jar bsh.Interpreter script.bsh
```

```jsonc
{
  "type": "bsh",
  "request": "attach",
  "name": "BeanShell: Attach",
  "host": "127.0.0.1",
  "port": 4711
}
```

**Launch** — the extension starts the JVM for you, on a port it picks itself unless you set one:

```jsonc
{
  "type": "bsh",
  "request": "launch",
  "name": "BeanShell: Launch",
  "script": "${workspaceFolder}/script.bsh",
  "agentJar": "/path/to/bsh-debug-agent-1.0.0-SNAPSHOT.jar",
  "classpath": "/path/to/bsh-2.0b6.jar"
}
```

Both are available from **Run and Debug → create a launch.json → BeanShell**. The agent jar
comes from `./gradlew :agent:instrument:shadowJar` in the repository root (output under
`agent/instrument/build/libs/`); `classpath` must include whichever BeanShell jar your project
already embeds.

Program output (the JVM's own stdout/stderr, which is not part of the DAP session) goes to the
**"BeanShell Debug"** output channel, not the Debug Console — the agent has no `output` event for
it, the same way `print()` in a plain interpreter session writes straight to the console.

## Why launch doesn't just work like other debuggers

The agent's DAP channel **listens** rather than connecting out (a DAP client attaches to a
debuggee that is already running), so under `launch` this extension is the one spawning the
JVM — the agent's own `launch` request handler does nothing but answer success, since from the
agent's side the script is already running either way. The extension waits for the agent's
`DAP: listening` line on stdout before treating the port as ready, rather than probing the port
itself: `ServerSocket.accept()` on the agent side is called exactly once, so a throwaway test
connection would consume the one accept meant for the real session.

## What isn't supported, and why

Declared honestly in the adapter's capabilities rather than silently ignored, so VS Code doesn't
build UI for something that would then fail:

- **Pause** — a BeanShell thread can only stop where it calls the hook, so there is nothing to
  interrupt on demand.
- **Conditional, function and exception breakpoints, step-back, restart-frame.**

Stopping the session (rather than disconnecting) kills the JVM this extension launched; an
*attached* session leaves the target process alone either way, since it was never this
extension's to manage.

## Testing

`src/test/` drives a real, headless VS Code (`@vscode/test-electron` + Mocha) against the fixture
workspace in `src/test/fixtures/workspace/` -- the same script, breakpoint line and evaluate
expression [`agent/checks/07-dap-transport.sh`](../../agent/checks/07-dap-transport.sh) already
proves work over `DapChannel`, driven here through a real `launch` session instead of the
standalone `dap-client.py`, so it covers the one thing `07` cannot: this extension's own
`BshDebugAdapterDescriptorFactory.launch()` (port allocation, the `-javaagent` spawn, the
`DAP: listening` stdout watch). Assertions run against the DAP traffic via a
`DebugAdapterTracker` and `session.customRequest()`, since no UI is present to trigger
`stackTrace`/`scopes`/`variables`/`evaluate` by clicking. Completion is detected via
`onDidTerminateDebugSession` rather than a `terminated` DAP event, since `DapChannel` never
sends one -- the JVM exiting just drops the socket.

```bash
npm test               # needs a display
xvfb-run -a npm test   # headless / CI
```

`npm test` builds the agent jar itself, via the same `:agent:samples:printPaths` Gradle task
[`agent/checks/lib.sh`](../../agent/checks/lib.sh) uses. The first run also downloads and caches
a VS Code build under `.vscode-test/`.

## Alternatives

[`../neovim/`](../neovim/) and [`../eclipse/`](../eclipse/) cover the same transport for those
editors.
