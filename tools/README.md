# tools/

Command-line helpers for exercising the BeanShell **debug transport** end-to-end
**without the IDE** — instrument a `.bsh` script, run it through the real
BeanShell interpreter with the debug agent attached, and watch the step-by-step
protocol from a stand-in "IDE" on the other end of the socket.

This is the same wire protocol the plugin's `BshDebugProcess` speaks over XDebug;
here you drive it by hand, so you can reproduce and inspect a debug session from a
plain terminal.

## The pieces

| File | Role |
|------|------|
| `bshInstrumenter.main.kts` | Reads a `.bsh` script on **stdin**, writes an instrumented copy to **stdout**. Before every safe statement it prepends `cz.loplex.intellij.bsh.debug.agent.BshDebugAgent.step(line, this.namespace);`. |
| `run-debug-bsh.sh` | Instruments a script (via the above) and runs it through `bsh.Interpreter`, with `BshDebugAgent` on the classpath from `build/`. Given a port, tells the agent to connect there. |
| `mock-ide.py` | Stands in for the IDE end of the transport. Listens on a port, prints every step frame (`line`, `callDepth`, variables), and immediately replies "resume" so the script runs to completion. |

## Running them together

The agent connects to a debug server only when a port is configured, so you need
two terminals:

```bash
# Terminal 1 — the stand-in IDE
./tools/mock-ide.py 47784

# Terminal 2 — instrument + run, wired to that port
./gradlew compileJava                       # once: builds BshDebugAgent into build/
./tools/run-debug-bsh.sh 47784 < samples/showcase.bsh
```

Terminal 1 prints one `STEP …` line per executed statement (with recursion depth
and the caller's variables); Terminal 2 shows the script's own output. Omit the
port to just run the instrumented script normally — with no server to reach, the
agent disables itself.

## Why instrumentation works differently here

The plugin's production instrumenter (`debug/BshDebugInstrumenter.kt`) walks the
**PSI tree** (`BshFile`, IntelliJ `ASTNode`, `BshElementTypes`) to find statement
boundaries. Those PSI classes only exist inside a running IntelliJ Platform, so
they are **not available** in a plain `kotlin`/`java` process on the command line.

`bshInstrumenter.main.kts` therefore uses a completely different mechanism: it
parses the script with BeanShell's own `bsh.Parser` and inspects the resulting
`SimpleNode`/`Node` tree — reached via **reflection**, since those types are
package-private. A line qualifies for a hook only when inserting the `step(...)`
call there leaves the parse tree otherwise identical (a "pure insertion"), which
rejects unsafe spots like a brace-less `if`/`while`/`for` body. Same end result
as the PSI instrumenter, arrived at without any IDE classes.
