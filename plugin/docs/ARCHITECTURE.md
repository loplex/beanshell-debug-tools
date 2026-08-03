# Architecture

This document explains how the BeanShell plugin is built. It complements the
[README](../README.md).

## Why hand-written lexer & parser

IntelliJ cannot consume the BeanShell grammar
([`bsh.jjt`](https://github.com/beanshell/beanshell/blob/2.0b6/src/bsh/bsh.jjt))
directly — that is a JavaCC/JJTree grammar, which produces a standalone,
non-incremental, non-error-tolerant parser. IntelliJ instead needs a `Lexer` and
a `PsiParser` built on `PsiBuilder`. Both are hand-written here, transcribed from
the grammar's token and production rules. This avoids extra build tooling
(Grammar-Kit/JFlex), is fully error-tolerant, and does not depend on any BeanShell
runtime version.

> `bsh.jjt` is the authored grammar (with JJTree `#Node` tree annotations).
> JJTree generates `bsh.jj` from it, and JavaCC generates the parser from `bsh.jj`
> — so `.jjt` is the source of truth we transcribe, and `.jj` is just an
> intermediate.

## Layers

```
BshLexer ──▶ tokens ──▶ BshParser ──▶ AST (PSI) ──▶ language features
```

### Lexer — `lexer/BshLexer.kt`
A `LexerBase` that tiles the whole input with tokens: comments (line / `#!` /
block / doc), keywords, identifiers, integer & floating literals (with radix and
type suffixes), string/char literals (including triple-quoted), separators, and
the full operator set including BeanShell word operators (`@gt`, `@and`,
`@pow_assign`, …). It is a superset of 2.0b6 so 3.0 tokens do not break
highlighting.

### Parser — `parser/BshParser.kt`
A recursive-descent `PsiParser`. The BeanShell grammar relies on JavaCC syntactic
lookaheads; these are reproduced with **bounded backtracking**: an alternative is
attempted behind a `PsiBuilder.Marker` and `rollbackTo()` rewinds on mismatch.
This mirrors the grammar's ordered choice while staying tolerant of incomplete
input. Composite node types are defined in `psi/BshElementTypes.kt`.

### PSI — `psi/`
`BshParserDefinition` maps AST node types to PSI classes. Declarations that
introduce a name (`BshClassDeclaration`, `BshMethodDeclaration`,
`BshVariableDeclarator`, `BshFormalParameter`) extend `BshNamedElement`
(`PsiNameIdentifierOwner`) to enable rename / find-usages / Go to Symbol.
`BshAmbiguousName` carries the references used for navigation.

## Name resolution — `reference/`

- **`BshResolver`** — BeanShell-local resolution: methods and classes are
  file-global (and project-global as a fallback); typed variables and parameters
  resolve in the nearest enclosing scope (`BshScopes`); untyped variables have no
  declaration node, so the *first assignment* to a simple name acts as one.
- **`BshTypeInference`** — best-effort static type of a variable (typed
  declaration/parameter, or `= new Type()`).
- **Java integration** (all guarded by `BshJavaSupport`, so the plugin loads
  without the Java plugin):
  - `BshJavaResolver` — resolves class names and members via `JavaPsiFacade`.
  - `BshChainResolver` — the type-propagation engine: walks a primary expression
    left-to-right, propagating a method's return type / a field's type to the
    next member, so Ctrl+Click works through chains such as
    `a.b().c().d`. BeanShell class members are resolved directly against the
    script's own class declarations.

References are produced lazily by `BshAmbiguousName.getReferences()` (segments of
a dotted name) and `BshPrimarySuffix.getReference()` (`.member` after a call).

## Feature wiring

Everything is registered in [`META-INF/plugin.xml`](../src/main/resources/META-INF/plugin.xml).
Optional integrations are isolated in separate descriptors loaded via optional
`<depends>`:

| Descriptor             | Loaded when          | Provides                       |
|------------------------|----------------------|--------------------------------|
| `bsh-java.xml`         | Java plugin present  | Java navigation / debug attach |
| `bsh-xml.xml`          | XML module present   | Maven/XML language injection   |
| `bsh-spellchecker.xml` | Spellchecker present | comment/string spell-checking  |

Packages by concern: `highlight`, `editor` (folding, brace matcher, commenter,
surround), `formatting`, `structure`, `navigation`, `completion`, `inspection`,
`intention`, `findusages`, `template`, `injection`, `run`, `debug`.

## Running & debugging

- `run/` builds a forked JVM command (`BshLaunch`) running
  `bsh.Interpreter <script>` with the bundled or configured classpath and the
  project JDK.
- `debug/` implements a source-level debugger by **instrumenting** the script and
  talking to an injected Java agent over a socket. See
  [DEBUGGING.md](DEBUGGING.md).

## Tests

`src/test` covers the lexer, parser (no-error parses + node presence), resolution
/ rename / find-usages, editor features, inspections, type inference, chain
navigation (against a real project Java class), and the debug transport
(instrumented script driving the agent over a socket on the real interpreter).
