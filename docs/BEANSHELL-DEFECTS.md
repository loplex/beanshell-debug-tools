# BeanShell defects found while building the debugger

Real, isolated against **BeanShell 2.0b6**, and recorded here because all three
affect a debugger specifically — they are not general-purpose curiosities. None of
them is caused by this repository's code, and none can be fixed from it: the
motivating case is a bsh already bundled inside a third-party library.

## 1. Redefining a method does not replace it

`NameSpace.setMethod` (`NameSpace.java:880-900`) never overwrites: it builds a
`Vector` of overloads and appends, and `getMethod` returns the first signature
match — the *old* one.

This is the worst trap for any re-source / hot-reload workflow. The user edits a
file, re-sources it, and then steps through new text while the interpreter runs the
old tree.

## 2. Scripted-class members must be `public` to be subclassable

Otherwise `new Point3D(1,2,3)` throws:

```
IllegalAccessError: tried to access method Point.<init>(II)V
```

Each generated class gets its own `BshClassLoader`, so "same package" does not hold
at runtime.

## 3. `return` inside a `try` loses its value if the `try` has any `finally`

Even an empty one. `BSHTryStatement.java:173` overwrites `ret` unconditionally,
discarding the `ReturnControl`. Repro:

```java
f() { try { return "ok"; } finally { } } f();   // yields void
```

Relevant here because `ReturnControl` propagation is what "step out" has to follow,
so the return value shown for such a frame will be wrong regardless of the agent.
