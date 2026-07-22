<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# BeanShell plugin Changelog

## [Unreleased]

### Added

- BeanShell (`.bsh`) language support built on a hand-written lexer and a
  full recursive-descent AST parser (BeanShell 2.0b6 grammar).
- Editing: syntax highlighting with a color settings page, code folding
  (blocks, comments, imports), brace matching, commenter, indentation
  formatter, structure view, breadcrumbs, read/write highlighting, quick
  documentation, live & postfix templates, Surround With, and TODO support.
- Code intelligence: Go to Declaration, Find Usages and Rename (methods,
  classes, typed/untyped variables, parameters; cross-file methods/classes),
  completion, parameter info, inlay parameter hints, Go to Symbol, the
  Introduce Variable intention, and inspections with quick fixes (unused
  variable, unreachable code, opt-in unresolved method call).
- Java interoperability (optional Java plugin): Ctrl+Click into Java classes
  and members through static type propagation across chains, plus navigation to
  BeanShell class members.
- Running: a BeanShell run configuration with the interpreter bundled
  (`org.apache-extras.beanshell:bsh:2.0b6`), project-JDK aware, with a
  right-click run producer.
- Debugging: a source-level debugger (line breakpoints, variables, step
  over/into/out via call-depth) implemented by script instrumentation and an
  injected agent; optional companion Java (JDWP) session for breakpoints in the
  Java code a script calls.
- File recognition & injection: `.bsh` files, BeanShell shebangs (including the
  self-executing polyglot), inline scripts in Maven plugin `<configuration>`
  (curated, classpath-configurable list), and `<!--language=BeanShell-->`
  comment injection in any XML.
