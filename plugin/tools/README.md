# tools/

Command-line helpers for exercising the BeanShell **debug transport** end-to-end
**without the IDE** — instrument a `.bsh` script, run it through the real
BeanShell interpreter with the debug agent attached, and watch the step-by-step
protocol from a stand-in "IDE" on the other end of the socket.

This is the same wire protocol the plugin's `BshDebugProcess` speaks over XDebug;
here you drive it by hand, so you can reproduce and inspect a debug session from a
plain terminal.

## The pieces

<table>
<thead>
  <tr>
    <th>File</th>
    <th>Role</th>
  </tr>
</thead>
<tbody>
  <tr>
    <td><code>bshInstrumenter.main.kts</code></td>
    <td>
      Reads a <code>.bsh</code> script on <strong>stdin</strong>, writes
      an instrumented copy to <strong>stdout</strong>. Before every safe
      statement it prepends
      <code>cz.loplex.intellij.bsh.debug.agent.BshDebugAgent.step(line, this.namespace);</code>.
    </td>
  </tr>
  <tr>
    <td><code>run-debug-bsh.sh</code></td>
    <td>
      Instruments a script (via the above) and runs it through
      <code>bsh.Interpreter</code>, with <code>BshDebugAgent</code> on the
      classpath from <code>build/</code>. Given a port, tells the agent to
      connect there.
    </td>
  </tr>
  <tr>
    <td><code>mock-ide.py</code></td>
    <td>
      Stands in for the IDE end of the transport. Listens on a port,
      prints every stop (<code>line</code>, <code>callDepth</code>, the call
      stack) with frame 0’s variables, and replies “resume” so the script runs
      to completion. Options exercise the rest of the protocol:
      <code>--breakpoints file.bsh:25,…</code> pushes a breakpoint set, which
      makes the agent filter locally instead of reporting every statement;
      <code>--expand</code> opens every expandable value one level;
      <code>--eval 'x + 1,twice(x)'</code> evaluates in frame 0;
      <code>--set count=99</code> assigns there.
    </td>
  </tr>
  <tr>
    <td><code>check-instrumentation.py</code></td>
    <td>
      Checks an implementation against the <code>HOOK</code> /
      <code>NO-HOOK</code> / <code>REWRITE-ONLY</code> /
      <code>AGENT-ONLY</code> markers in
      <code>samples/instrumentation-boundaries.bsh</code>, so those markers
      cannot rot. <code>--target rewriter</code> (static, checks every marked
      line) or <code>--target agent</code> (dynamic, checks the lines a run
      actually reaches).
    </td>
  </tr>
</tbody>
</table>

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
