#!/usr/bin/env bash
#
# End-to-end test of bsh-dap.lua: runs run.lua headless under a real nvim-dap, against the real
# agent. Needs `nvim` (0.9+, for the `-l` script runner) and `git` on PATH; this repo does not
# vendor nvim-dap, since it is nvim-dap's own -- not this repo's -- code under test.
#
# Usage: ./editors/neovim/tests/run-tests.sh

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
TESTS_DIR="$(pwd)"
REPO_ROOT="$(cd ../../.. && pwd)"
GRADLEW="$REPO_ROOT/gradlew"

if ! command -v nvim >/dev/null; then
    echo "nvim not found on PATH" >&2
    exit 1
fi

# Pinned to a commit, not a moving branch: nvim-dap carries no version tags, and a plugin this
# test only wires through bsh-dap.lua should not start failing because an unrelated upstream
# commit changed something this test happens to touch.
NVIM_DAP_REV="9e848e09a697ee95302a3ef2dd43fd6eb709e570"
DEPS_DIR="$TESTS_DIR/.deps"
NVIM_DAP_DIR="$DEPS_DIR/nvim-dap"
if [[ ! -d "$NVIM_DAP_DIR" ]]; then
    mkdir -p "$DEPS_DIR"
    git clone -q https://github.com/mfussenegger/nvim-dap.git "$NVIM_DAP_DIR"
fi
if [[ "$(git -C "$NVIM_DAP_DIR" rev-parse HEAD)" != "$NVIM_DAP_REV" ]]; then
    git -C "$NVIM_DAP_DIR" checkout -q "$NVIM_DAP_REV"
fi

echo "Resolving the agent jar and BeanShell classpath..."
paths="$("$GRADLEW" -q -p "$REPO_ROOT" :agent:samples:printPaths)"
BSH_CLASSPATH="$(grep '^BSH_CLASSPATH=' <<<"$paths" | cut -d= -f2-)"
AGENT_JAR="$(grep '^AGENT_JAR=' <<<"$paths" | cut -d= -f2-)"
if [[ -z "$BSH_CLASSPATH" || -z "$AGENT_JAR" ]]; then
    echo "could not parse :agent:samples:printPaths output:" >&2
    echo "$paths" >&2
    exit 1
fi

REPO_ROOT="$REPO_ROOT" \
NVIM_DAP_DIR="$NVIM_DAP_DIR" \
BSH_AGENT_JAR="$AGENT_JAR" \
BSH_CLASSPATH="$BSH_CLASSPATH" \
FIXTURE_SCRIPT="$TESTS_DIR/fixtures/script.bsh" \
    nvim --headless -u NONE -l "$TESTS_DIR/run.lua"
