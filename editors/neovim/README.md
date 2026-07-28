# BeanShell debugging in Neovim

Wires the [debug agent](../../agent/README.md)'s
[DAP transport](../../docs/PROTOCOL.md#9-relationship-to-dap) into
[nvim-dap](https://github.com/mfussenegger/nvim-dap). Same agent, same protocol as
[`../vscode/`](../vscode/) — this is packaging for a different editor, not a second
implementation.

## Setup

Drop [`bsh-dap.lua`](bsh-dap.lua) somewhere on `runtimepath` (e.g.
`~/.config/nvim/lua/bsh-dap.lua`) and, after `nvim-dap` is loaded:

```lua
require('bsh-dap').setup()
```

This registers the `bsh` adapter, associates the `.bsh` extension with the `beanshell`
filetype, and adds two entries to `require('dap').configurations.beanshell` — edit their
`agentJar`/`classpath` defaults (or answer the prompts they open) to match your setup.

## Attach vs. launch

**Attach**, to a JVM you already started:

```bash
java -javaagent:/path/to/bsh-debug-agent-1.0.0-SNAPSHOT.jar \
     -Dbsh.debug.protocol=dap -Dbsh.debug.listen=4711 \
     -Dbsh.debug.sources=script.bsh \
     -cp /path/to/bsh-2.0b6.jar bsh.Interpreter script.bsh
```

```lua
{
  type = 'bsh',
  request = 'attach',
  name = 'BeanShell: Attach',
  host = '127.0.0.1',
  port = 4711,
}
```

**Launch** starts the JVM for you — `bsh-dap.lua` spawns it, waits for the agent's
`DAP: listening` line on stdout (the agent's `ServerSocket.accept()` is called exactly once, so
probing the port with a test connection would consume the one accept meant for the real
session), and only then hands nvim-dap a `server` adapter descriptor:

```lua
{
  type = 'bsh',
  request = 'launch',
  name = 'BeanShell: Launch',
  script = '/path/to/script.bsh',
  agentJar = '/path/to/bsh-debug-agent-1.0.0-SNAPSHOT.jar',
  classpath = '/path/to/bsh-2.0b6.jar',
}
```

Both accept the same fields as the VS Code extension's launch configuration
(`sources`, `sourcesFile`, `args`, `cwd`, `env`, `vmArgs`) — see
[`../vscode/README.md`](../vscode/README.md) for what each does. A launched JVM's own stdout is
not part of the DAP session (the agent has no `output` event for it) and is left in whatever
buffer `jobstart` reports it to — check `:messages` or wire `on_stdout` yourself if you want it
somewhere more visible.

## What isn't supported

Same limits as the agent everywhere else, declared in its DAP capabilities rather than silently
ignored: no pause (a BeanShell thread only stops where it calls the hook, so there is nothing to
interrupt), no conditional/function/exception breakpoints, no step-back, no restart-frame.
