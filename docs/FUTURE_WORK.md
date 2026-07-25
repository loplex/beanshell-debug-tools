# Future work

Ideas and known limitations worth revisiting. Not scheduled — a parking lot so
they are not lost to conversation history.

## Richer debug-variable protocol (beyond `toString()`)

The debug transport reports each variable as a single `toString()` string
(`BshDebugAgent.readVariables` → `writeUTF`). That is simple and robust — no
dangling references to objects living in the foreign JVM — but it has real
limits:

- **No structure.** Nested objects and collections cannot be expanded in the
  Variables panel; the developer only sees one flat line per variable.
- **Hard size cap.** `DataOutputStream.writeUTF` tops out at 65535 bytes, so
  values are truncated (`MAX_VALUE_LENGTH`); large objects lose their tail.
- **Eager, per-step.** Every value is serialized on every step, even when the
  developer never looks at it.

A richer design would replace "name → String" with an object-handle model: send
lightweight handles, and let the IDE request a specific object's members
on demand (lazy expansion). This touches both `BshDebugAgent` and the IDE side
(`BshDebugProcess`, `BshDebugFrames`) and is a protocol change, so it is a
deliberate second iteration rather than a tweak.

## Non-zero exit from `tools/run-debug-bsh.sh` on script errors

`bsh.Interpreter` prints a "Target exception" but still exits `0` when a script
fails to evaluate (the connect-failure case is already handled — the agent calls
`System.exit(69)`). For the command-line tools it would be nicer if a failing
script produced a non-zero exit, so callers/CI can detect it. This is a
wrapper-level concern (parse the interpreter's output, or run the script via a
small launcher that propagates eval errors), not an agent change.

## (Optional) Second JDWP channel for step-into Java

The original inline-debug plan left one optional item unimplemented: a second,
JDWP-based debug channel (reuse `BshJavaDebugAttach`) so the developer can step
*into* the Java code a Maven-run script calls, in addition to line-stepping the
script itself. Independent of the script-level transport; purely additive.
