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
| `mock-ide.py` | Stands in for the IDE end of the transport. Listens on a port, prints every step frame (`line`, `callDepth`, variables), and replies "resume" so the script runs to completion. With `--breakpoints file.bsh:25,…` it also pushes a breakpoint set, which makes the agent filter locally instead of reporting every statement. |
| `check-instrumentation.py` | Checks an implementation against the `HOOK` / `NO-HOOK` / `REWRITE-ONLY` / `AGENT-ONLY` markers in `samples/instrumentation-boundaries.bsh`, so those markers cannot rot. `--target rewriter` (static, checks every marked line) or `--target agent` (dynamic, checks the lines a run actually reaches). |

## Running them together

The agent connects to a debug server only when a port is configured, so you need
two terminals:

```bash
# Terminal 1 — the stand-in IDE
./tools/mock-ide.py 47784

# Terminal 2 — instrument + run, wired to that port
../gradlew :plugin:compileJava              # once: builds BshDebugAgent into build/
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

## Checking that instrumentation lands where it should

`samples/demo.bsh` and `samples/showcase.bsh` are written so plainly that *every*
line is a valid hook position, so they cannot distinguish a working instrumenter
from one that blindly prefixes every line.
`samples/instrumentation-boundaries.bsh` exists for that: it mixes legal and
illegal positions and marks each one, and `check-instrumentation.py` verifies the
markers against reality.

```bash
./tools/check-instrumentation.py                                   # the rewriter
./tools/check-instrumentation.py --target agent \
    --bsh-classpath /path/to/bsh/classes                           # the agent
```

Two of the four marker categories record a real, deliberate difference between the
two implementations rather than a defect in either:

- **`REWRITE-ONLY`** — the rewriter legally inserts before a `case` label or a
  `} catch (e) {` line, but the hook joins the *end of the preceding block* and
  then reports that line, where execution is not. The agent stays silent, since no
  AST node starts there. The same applies to an empty statement, which the grammar
  gives no node at all.
- **`AGENT-ONLY`** — a brace-less body (`if (t) foo();` with `foo()` on the next
  line) is a genuine statement the agent can stop on, while the rewriter must not
  touch it: prefixing it would leave the `if` governing the hook instead of the
  body, so the body would run unconditionally.

The first label in a `switch` is `NO-HOOK` for both, incidentally — there is no
preceding statement for a hook to attach to, so the rewriter's pure-insertion test
rejects it where it accepts later labels.
