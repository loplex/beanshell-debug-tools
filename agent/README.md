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

Version 2. Both directions are opcode-tagged.

```
agent -> IDE
  0x10 STOPPED    int line, int callDepth, int frameCount,
                  (utf name, utf sourceFile, int line)*     innermost frame first
  0x11 SCOPES     int count, (utf name, int handle)*        answers 0x04
  0x12 VARIABLES  int count,
                  (utf name, utf value, utf type, int childHandle)*   answers 0x05
  0x13 EVALUATED     byte ok, utf value, utf type, int childHandle    answers 0x06
  0x14 VARIABLE_SET  byte ok, utf value, utf type, int childHandle    answers 0x07

IDE -> agent
  0x01 RESUME
  0x02 SET_BREAKPOINTS  int count, (utf file, int line)*
  0x03 SET_RUN_MODE     byte mode                           0 = running
  0x04 SCOPES           int frameId                         only while suspended
  0x05 VARIABLES        int handle                          only while suspended
  0x06 EVALUATE         int frameId, utf expression         only while suspended
  0x07 SET_VARIABLE     int frameId, int handle,
                        utf name, utf expression            only while suspended
```

**Variables are pulled, not pushed.** Each expandable value carries an opaque
handle, and the IDE asks for its children only if the user opens it. A handle is
valid until the next resume and the table is dropped there, so the IDE can never
hold a reference into a script that has moved on — no stale-object problem to
solve, no cleanup protocol to get wrong. That is
[DAP's `variablesReference`](https://microsoft.github.io/debug-adapter-protocol/specification#Types_Variable)
in a smaller encoding: adopting DAP later changes the serialisation, not the design.

Requests are served **on the interpreter thread**, from inside the same loop that
waits for `RESUME`. Not a shortcut: that thread is parked there anyway, it owns
the BeanShell state being inspected, and answering from anywhere else would need a
lock BeanShell does not offer. It also means a reply is always the next thing on
the wire, so neither end needs request ids.

There is no version negotiation, because there is nothing to negotiate with: the
agent jar ships inside the plugin, so both ends are always the same build.

Two consequences to keep in mind. The **first statement is always reported**,
because the agent opens the connection on its first report and cannot know the
breakpoints sooner. And breakpoint filtering (`SET_BREAKPOINTS`) is only enabled
where the line mapping is the identity (the standalone `.bsh` path): breakpoints
must be expressed in the lines the agent reports, and the pom mapper has no
inverse. The injected-pom path instead reports every statement of its own script
and lets the IDE decide — correct, and no slower than it sounds, since an inline
`<script>` is a handful of lines.

### Which sources to report on

Instrumenting the interpreter reaches strictly more code than rewriting one script
does, so a filter is not optional: BeanShell's own commands (`print`, `pwd`, …) are
`.bsh` files on the classpath, and without a filter the session stops inside
`print.bsh` on every `print()` call. Two properties, ORed:

| property | match | for |
|---|---|---|
| `bsh.debug.sources` | comma-separated, `endsWith` | a script that has a file name |
| `bsh.debug.sources.file` | a file of prefixes, one per line, `startsWith` | a script handed over as a **string** |

The second exists because a string has no file name. BeanShell invents one:
`Interpreter.eval(String)` names the source

```
inline evaluation of: ``<the script, newlines flattened, cut at 80 chars + " . . . ">''
```

after appending a `;` if the script lacked one. That is the shape an inline Maven
`<script>` arrives in, and it has to be matched by a **prefix** — the tail may be
the elision, and the script's own text sits in the middle. A prefix short enough to
stay inside the 80 characters is therefore immune to the cut, to the appended `;`
and to any trimming the calling plugin did. A *file* rather than a property value
because these strings contain the script's own punctuation, commas included; a
newline, by contrast, cannot occur inside one — BeanShell already replaced every
newline with a space when it built the name.

`BshMavenDebugSupport.beanShellSourceName` reproduces the whole rule, which is what
pins the prefix down as correct.

### The frame positions are not where you would first look

`NameSpace.getInvocationLine()` answers "where was I called *from*", so it
describes a position in the next frame out, not in its own. Frame *k* is therefore
placed at the call site recorded by frame *k-1* (`NameSpace.callerInfoNode`, which
carries the source file too), and frame 0 at the statement being reported. Reading
`getInvocationLine()` off each frame directly yields a stack that is off by one
level and looks plausible.

### Evaluating, and changing a value

`EVALUATE` hands the expression to `Interpreter.eval(String, NameSpace)` with the
frame's own namespace, so a watch sees exactly what the script sees there — its
variables, its methods, its imports — rather than a reimplementation of BeanShell's
name resolution. Two properties of that call shape the design:

- **It returns plain Java.** `1+1` comes back as `java.lang.Integer`, `null` as a
  real `null` — unlike a namespace lookup, which hands back the `bsh.Primitive`
  that wraps every scripted number. So an evaluated result needs no unwrapping
  before it is rendered or stored, and reflective `field.set`/`Array.set` accept it
  directly.
- **It runs the real assignment path.** `SET_VARIABLE` on a variable in scope is
  served by evaluating `name = (expression)` rather than by calling
  `NameSpace.setVariable`, which means BeanShell applies its own rules: a typed
  variable refuses an incompatible value (`Can't assign java.lang.String to int`)
  exactly as the script would, and a variable inherited from an enclosing scope is
  updated where it was declared instead of being shadowed. Anything that is not a
  namespace — a field, an array element, a list slot, a map entry — has no
  expression that names it and is reached reflectively instead.

A failure is an ordinary reply with `ok = 0`, not a dropped connection: a mistyped
watch expression is normal use. BeanShell's messages lead with the source and an
echo of the expression, so only the tail of the first line is sent on.

The re-entrancy guard is what makes any of this safe — an evaluated expression may
call a script method, and that method's statements are instrumented too. The guard
is held for the whole of a stop, so everything served while suspended is covered.

Neither request is available on the plugin's *source-rewriting* fallback: a
rewritten script hands the hook a `NameSpace`, and a namespace cannot evaluate. It
answers `ok = 0` with that reason rather than letting an opcode it does not serve
fall through to "resume", which would silently continue the script.

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
