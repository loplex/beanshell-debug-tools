# The BeanShell debug agent

A JVM agent that instruments `bsh.Interpreter` so BeanShell scripts can be debugged
at the source level, without modifying the script or the library that embeds it.

Two subprojects, and the split is a runtime constraint rather than tidiness:

```
agent/instrument/   :agent:instrument   bsh-debug-agent  -- premain + the ASM transformer, shaded
agent/hook/         :agent:hook         bsh-debug-hook   -- the class instrumented BeanShell calls into
```

```bash
./gradlew :agent:instrument:shadowJar    # -> agent/instrument/build/libs/bsh-debug-agent-*.jar
```

Java 8, because the agent loads into whatever JVM the host library runs on. The jar
is self-contained: ASM is shaded and relocated, and the hook travels inside it as a
nested jar.

---

## 1. Why an agent, and why not JDWP

The goal is debugging BeanShell inside **third-party libraries that already bundle
bsh** — Maven plugins such as maven-enforcer being the motivating case. The code is
fixed; only runtime behaviour can be changed. That rules out patching BeanShell and
makes an agent the only vehicle.

**JDWP is not usable, and the reason is a language property rather than an
implementation gap.** JDWP's `Location` is `(typeID, methodID, bytecodeIndex)`, so
it needs a line-number table and local-variable slots. BeanShell does emit real
bytecode, but only for scripted classes, and only as a shim. Measured on this
build with `-DsaveClasses`:

```
$ javap -p -l Point
Compiled from "BeanShell Generated via ASM (www.objectweb.org)"
```

No `LineNumberTable`, no `LocalVariableTable`, and `SourceFile` is a literal
advertising string rather than a path. The only `visitLineNumber` call in
`ClassGeneratorUtil` is commented out (`:595`), and the disassembly explains why
there is nothing to attach a line to — the whole body of `int getX()` is 29 bytes
and one call:

```
1: getfield  _bshThisPoint:Lbsh/This;
4: ldc       "getX"
16: invokevirtual bsh/This.invokeMethod
```

Making bytecode useful would mean compiling BeanShell properly, and the debugger
value of bytecode lives in the variable table — but BeanShell variables cannot live
in JVM slots while closures exist, because a closure is a `NameSpace` kept alive by
a `This` reference after its frame is gone. So the barrier is the language, not the
backend.

**Kotlin is the same statement from the other side.** It gets JDWP for free because
it compiles to real bytecode with full debug info, and where its model diverges it
patches the line table rather than the protocol: inline function bodies are copied
into the caller and described by SMAP (`SourceDebugExtension`) with a custom
`KotlinDebug` stratum, and the compiler even inserts a *fake* line number so the
debugger can see the boundary between a function body and an inline lambda. And
where the divergence was structural — coroutines, whose logical stack is not the
JVM stack — JetBrains did **not** fake JDWP. `kotlinx-coroutines-debug` is a JVM
agent installed via ByteBuddy that replaces `DebugProbesKt` and keeps its own
records, which the IDE renders in a separate panel. Instrumenting agent plus
IDE-side presentation, alongside JDWP, by the people who owned both ends.

Two sessions on one JVM **do** coexist (verified in practice): a compound run
configuration starts both, and you get two independent Debug tabs. So the plugin
already runs the BeanShell session next to a Java one, and the agent does not need
to reproduce anything the Java debugger provides.

---

## 2. How it works

`EvalTransformer` prepends four instructions to every method matching

```
eval(Lbsh/CallStack;Lbsh/Interpreter;)Ljava/lang/Object;
```

which is the BeanShell AST contract. **No class is named**, so nodes added,
removed or renamed between BeanShell releases are handled automatically — the
version independence is structural, not a list to maintain. Classes are discovered
by that signature within the `bsh` package only, which also keeps the agent away
from BeanShell's own bundled ASM in `bsh.org.objectweb.asm`.

The hook takes `Object` parameters because it is loaded by the **bootstrap
classloader** and therefore cannot link against `bsh.CallStack` even though that
class is public. Bootstrap is required because BeanShell may live in a classloader
that cannot see the application classpath — the Maven plugin case. It ships as a
nested jar extracted at premain, so no loose hook classes sit in the agent jar and
the system loader cannot define a second copy.

### Landmines, all of which cost real time

- **Two copies of a class is the recurring hazard.** Appending the whole agent jar
  to bootstrap made every agent class definable twice; parent-first delegation then
  loaded `EvalTransformer` from bootstrap while `BshAgentMain` stayed in the system
  loader, and package-private access between them threw `IllegalAccessError`. Fixed
  structurally (nested jar), not by widening visibility. **Agent code must never
  reference `BshHook` by type** — configuration travels through system properties,
  which are loader-independent, and the hook appears only as a string constant.
- `ClassWriter.COMPUTE_MAXS` only, never `COMPUTE_FRAMES`. The latter resolves
  common superclasses, which loads classes from inside a transformer.
- A re-entrancy guard is mandatory: reading variables runs BeanShell code, which is
  itself instrumented.
- **A container's children are not all statements.** `BSHForStatement`'s children
  are the init list, the condition, the update list *and* the body, all reporting
  the `for` line. The child layouts were read off real parse trees:

  | node | children | statement position |
  |---|---|---|
  | `BSHIfStatement` | `[cond, then, else?]` | index ≥ 1 |
  | `BSHWhileStatement` (`while`) | `[cond, body]` | last |
  | `BSHWhileStatement` (`do`) | `[body, cond]` | **first** |
  | `BSHForStatement` | `[init?, cond?, update?, body]` | last |
  | `BSHEnhancedForStatement` | `[type?, iterable, body]` | last |
  | `BSHSwitchStatement` | `[expr, label, stmt, …]` | index ≥ 1 |

  `do` and `while` are the *same node type* (`DoStatement() #WhileStatement`) with
  opposite child order, separated by the package-private `isDoStatement` field.
  Several productions reuse foreign nodes: `synchronized` is `#Block`,
  `break`/`continue` are `#ReturnStatement`, and `StatementExpression()` has no
  node of its own — which is why `foo();` is a `BSHMethodInvocation`,
  indistinguishable by class from a sub-expression, and why statement position has
  to be decided from the parent rather than from the node's type.
- `SimpleNode` and `Node` are **package-private**, so their public methods need
  reflection with `setAccessible`. `CallStack`, `NameSpace` and `Interpreter` are
  public.
- `CallStack.depth()` grows by exactly +1 per **method** frame and is unaffected by
  blocks and loops, because `BSHBlock` uses `swap()` rather than `push()`. It is
  O(1), unlike counting `bsh.BshMethod` frames in a captured stack trace.

### Not bit-transparent

Behaviour is unchanged — every fixture produces identical output with and without
the agent — but **identity hash codes shift** deterministically (`Point@279f2327`
becomes `Point@30f39991` and stays there), because initialising the hook on the
interpreter thread advances that thread's identity-hash sequence. Nothing correct
depends on those values, but a script printing a default `toString()` shows
different digits.

---

## 3. Protocol

Agent to IDE, per reported statement:

```
int line, int callDepth, int varCount, (utf name, utf value)*
```

IDE to agent:

```
0x01                                      resume
0x02  int count  (utf file, int line)*    set breakpoints
0x03  byte mode                           set run mode, 0 = running
```

Backward compatible in both directions: the byte that used to mean "resume" is
`0x01`, other commands merely precede it, and the agent reports **everything**
until it is given a breakpoint set at least once — so an IDE that configures
nothing is un-optimised rather than blind.

Two consequences to keep in mind. The **first statement is always reported**,
because the agent opens the connection on its first report and cannot know the
breakpoints sooner. And filtering is only enabled where the line mapping is the
identity (the standalone `.bsh` path): breakpoints must be expressed in the lines
the agent reports, and the injected-pom mapper has no inverse.

This protocol is deliberately provisional. The next step is to adopt DAP's
vocabulary — see [`docs/FUTURE_WORK.md`](../docs/FUTURE_WORK.md).

---

## 4. Verifying it

[`samples/`](samples/README.md) holds the fixtures: nine scripts covering one
execution path each, plus `DebugHost`, which embeds BeanShell the way a
third-party library does. Run them through both entry points — `Interpreter.run()`
and `Interpreter.eval()` are separate loops, and an agent that hooks only one looks
correct in the CLI and does nothing in a library.

```bash
./gradlew :agent:samples:runHost            # uninstrumented
./gradlew :agent:samples:runHostWithAgent   # the same, under the agent
```

The two must agree, which is what pins down "behaviour unchanged". The README
there lists the three differences that are legitimate.

The transport itself can be exercised without the IDE, and the instrumentation
boundaries are checked against a marked fixture; both live with the plugin's tools,
[`plugin/tools/README.md`](../plugin/tools/README.md).

Known BeanShell bugs met along the way are recorded in
[`docs/BEANSHELL-DEFECTS.md`](../docs/BEANSHELL-DEFECTS.md); all three affect a
debugger specifically.
