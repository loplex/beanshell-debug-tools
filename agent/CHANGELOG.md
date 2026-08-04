<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Debug agent Changelog

Covers the standalone debug agent (`bsh-debug-agent-X.Y.Z.jar`, built from
`:agent:instrument` with `:agent:hook` shaded in) as distributed on its own for the
VS Code, Neovim and Eclipse DAP transports. The copy of this jar bundled inside the
IDEA plugin has its own release cycle -- see [`../plugin/CHANGELOG.md`](../plugin/CHANGELOG.md).

## [Unreleased]

## [0.2.0] - 2026-08-04

### Added

- `SCOPES` now reports a `Block`/`Closure` level for each `for`/`if` body or captured
  closure namespace between a frame's `Locals` and `Global`, each carrying only its own
  directly-declared variables, instead of `Locals` flattening the whole parent chain into
  one group. See [`docs/PROTOCOL.md`](../docs/PROTOCOL.md#0x11-scopes--answers-0x04).
