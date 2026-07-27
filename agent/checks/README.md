# checks/

End-to-end checks for the debug agent, as standalone bash scripts.

```bash
./agent/checks/run-all.sh                        # everything
./agent/checks/02-maven-plugin-realm.sh          # one check, while working on it
```

Each script builds what it needs, prints one `PASS`/`FAIL` line per assertion, and exits
non-zero if any failed. `JAVA_HOME` is honoured; the agent targets Java 8, so anything
8+ works for the debugged JVM.

## Why these are not Gradle tests

Each one needs something a JVM test cannot arrange from inside itself:

| check | needs |
|---|---|
| `01-inline-eval-source-name.sh` | a JVM launched with `-javaagent`, so the interpreter is instrumented before it loads |
| `02-maven-plugin-realm.sh` | a **real `mvn` process**, because the thing under test is a Maven plugin's own classloader |
| `03-scopes-and-introspection.sh` | two processes and a socket between them — the actual wire protocol |
| `04-behaviour-unchanged.sh` | the same fixtures run twice, in separate JVMs, one with the agent |
| `05-two-script-threads.sh` | two real threads, suspended at the same time over one socket |
| `06-suspend-all.sh` | a thread stopping at a line that has no breakpoint on it |
| `07-dap-transport.sh` | a real DAP conversation, handshake included, over a socket |

They are also the checks you want *while* changing the agent, one at a time, with the
output in front of you — which is the other reason they are scripts.

`02` skips itself (exit 0) when `mvn` is not on `PATH`. A missing Maven is a reason not
to run it, not a failure of the agent.

## What each one is protecting

**`01` — the synthetic source name.** An inline Maven `<script>` never becomes a file, so
BeanShell invents a name for it from the script's own text
(``inline evaluation of: ``…''``) and the IDE matches a prefix of that name to know *which*
script reported. `BshMavenDebugSupport.beanShellSourceName` reproduces the rule. If a
BeanShell upgrade changed the wording, the 80-character elision or the appended `;`, Maven
debugging would stop resolving scripts — and the symptom would be a silent "no breakpoints
hit". This check turns that into a red line. It also pins the line numbering the pom line
map is built on.

**`02` — the Maven plugin realm.** The motivating case for the whole design; the hook sits
on the bootstrap classpath for no other reason. Also checks the source-prefix filter, and
computes the prefix *by the production rule* rather than hard-coding a string that could
drift from the code.

**`03` — scopes and expansion.** That a `bsh.This` expands to the **namespace** it stands
for rather than to its Java fields (one mechanism serving three things in the UI: a
scripted instance's `_bshThis…` field, a closure's captured scope, and a `This` handed back
to Java), and that `Global` appears when stopped inside a method — where a script's
top-level state would otherwise be invisible. Both regress silently: the panel still shows
*something*.

**`05` — two script threads.** The check the thread work exists for, and the one that could not
pass before it: under protocol 2 a suspended thread *was* the socket reader, so two suspended at
once was structurally impossible. Holds both parked simultaneously and asserts each reports its
own locals.

**`06` — Suspend: All.** That a thread stops at a line carrying **no breakpoint**, which is the
only observation that distinguishes the round-up from ordinary per-thread suspension. Gives each
thread its own code so the breakpoint can belong to one of them alone.

**`07` — the DAP transport.** That the same debugger works over DAP: the handshake in the right
order (a client that never sees `initialized` configures nothing), breakpoints honoured, a stack
with depth, both scopes, and an expression evaluated in the stopped frame. Between them those cover
every translation `DapChannel` performs. `dap-client.py` beside it is a standalone DAP client for
driving a session by hand, the way `mock-ide.py` is for the native protocol.

**`04` — behaviour is unchanged.** The agent must not change what a script does. Allows
exactly the three differences documented in [`../samples/README.md`](../samples/README.md)
(identity hashes, `NameSpace` addresses, thread interleaving) by normalising them, and
requires equality otherwise.

## Adding one

Source `lib.sh`, which provides `need_paths` (sets `BSH_CLASSPATH` and `AGENT_JAR`, asking
Gradle rather than guessing at the cache layout), `java_bin`, a temp directory in
`$CHECK_TMP` that is cleaned up on any exit, `assert_contains` / `assert_not_contains` /
`assert_equals`, `require_command`, and `finish` — which prints the tally and sets the exit
code. Name it `NN-what-it-checks.sh` and `run-all.sh` picks it up.

Prefer asserting on the repository's own fixtures (`../samples/scripts/`,
`../../plugin/samples/maven/`) over inline heredocs, so the fixtures and the checks cannot
drift apart. Where a check must construct something the production code also constructs —
the filter prefix in `02` — compute it by the same rule instead of pasting a literal.

## Related

- [`../samples/README.md`](../samples/README.md) — the fixtures, and the three legitimate
  differences under the agent
- [`../../plugin/tools/README.md`](../../plugin/tools/README.md) — `mock-ide.py` and the
  rewriting-path tools these build on
- [`../../docs/PROTOCOL.md`](../../docs/PROTOCOL.md) — the wire protocol `03` speaks
