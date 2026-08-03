# BeanShell defects found while building the debugger

Real, isolated against **BeanShell 2.0b6**, and recorded here because all three
affect a debugger specifically — they are not general-purpose curiosities. None of
them is caused by this repository's code, and none can be fixed from it: the
motivating case is a bsh already bundled inside a third-party library.

Each one has a fixture in [`agent/samples/`](../agent/samples/README.md) that
demonstrates it.

## 1. Redefining a method does not replace it

*Fixture:* `02_methods.bsh`

`NameSpace.setMethod` (`NameSpace.java:880-900`) never overwrites: it builds a
`Vector` of overloads and appends, and `getMethod` returns the first signature
match — the *old* one. In the fixture both prints say `first`, never `second`, and
the stale AST stays live for the rest of the session.

This is the worst trap for any re-source / hot-reload workflow. The user edits a
file, re-sources it, and then steps through new text while the interpreter runs the
old tree — breakpoints resolve against code that is not running.

## 2. Scripted-class members must be `public` to be subclassable

*Fixture:* `05_scripted_class.bsh`

Otherwise `new Point3D(1,2,3)` throws:

```
IllegalAccessError: tried to access method Point.<init>(II)V from class Point3D
```

Each generated class gets its own `BshClassLoader`, so "same package" does not hold
at runtime and package-private access fails.

## 3. `return` inside a `try` loses its value if the `try` has any `finally`

*Fixture:* `08_exceptions.bsh`, markers BP:5-8

Even an empty `finally`. The root cause is one line, `BSHTryStatement.java:173`:

```jshelllanguage
if (finallyBlock != null)
    ret = finallyBlock.eval(callstack, interpreter);
```

It overwrites `ret` unconditionally, discarding the `ReturnControl` produced by the
try/catch block. Minimal repro:

```java
@SuppressWarnings("EmptyFinallyBlock")
f() { try { return "ok"; } finally { } } f();   // yields void
```

Relevant here because `ReturnControl` propagation is what "step out" has to follow,
so the return value shown for such a frame will be wrong regardless of the agent.
