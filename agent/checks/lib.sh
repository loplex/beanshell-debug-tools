#!/usr/bin/env bash
#
# Shared plumbing for the checks in this directory. Sourced, not run.
#
# Every check is a standalone script: it builds what it needs, runs, prints one PASS or FAIL line
# per assertion and exits non-zero if any failed. That shape is deliberate -- these exercise things
# a Gradle test cannot reach (a real `mvn` invocation, a JVM launched with -javaagent, a socket
# conversation), so they have to be scripts, and being scripts they should be runnable one at a time
# while debugging the thing they check.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GRADLEW="$REPO_ROOT/gradlew"

CHECKS_PASSED=0
CHECKS_FAILED=0

# Every check works in its own temp directory and removes it on exit, however it exits.
CHECK_TMP="$(mktemp -d)"
trap 'rm -rf "$CHECK_TMP"' EXIT

pass() {
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
    printf '  \033[32mPASS\033[0m %s\n' "$1"
}

fail() {
    CHECKS_FAILED=$((CHECKS_FAILED + 1))
    printf '  \033[31mFAIL\033[0m %s\n' "$1"
    if [[ $# -gt 1 ]]; then
        printf '       %s\n' "$2"
    fi
}

# assert_contains <haystack-file> <needle> <description>
assert_contains() {
    if grep -qF -- "$2" "$1"; then
        pass "$3"
    else
        fail "$3" "not found: $2"
    fi
}

# assert_not_contains <haystack-file> <needle> <description>
assert_not_contains() {
    if grep -qF -- "$2" "$1"; then
        fail "$3" "unexpectedly found: $2"
    else
        pass "$3"
    fi
}

# assert_equals <expected> <actual> <description>
assert_equals() {
    if [[ "$1" == "$2" ]]; then
        pass "$3"
    else
        fail "$3" "expected [$1], got [$2]"
    fi
}

banner() {
    printf '\n\033[1m%s\033[0m\n' "$1"
}

# Ends a check: prints the tally and exits with the right code.
finish() {
    printf '\n%d passed, %d failed\n' "$CHECKS_PASSED" "$CHECKS_FAILED"
    [[ $CHECKS_FAILED -eq 0 ]]
}

# Sets BSH_CLASSPATH and AGENT_JAR, building the agent jar if needed.
#
# Asks Gradle rather than searching the cache: the BeanShell coordinates live in the version catalog
# and its jar sits under a content hash, so any path this script guessed would be wrong the moment
# either changed.
need_paths() {
    local out="$CHECK_TMP/paths.sh"
    if ! "$GRADLEW" -q -p "$REPO_ROOT" :agent:samples:printPaths > "$out" 2>"$CHECK_TMP/paths.err"; then
        printf 'cannot resolve paths; gradle said:\n' >&2
        cat "$CHECK_TMP/paths.err" >&2
        exit 1
    fi
    # shellcheck disable=SC1090
    source <(grep -E '^(BSH_CLASSPATH|AGENT_JAR)=' "$out")
    if [[ ! -f "$AGENT_JAR" ]]; then
        printf 'agent jar missing after build: %s\n' "$AGENT_JAR" >&2
        exit 1
    fi
}

# The java to launch debugged JVMs with. Not necessarily the one running Gradle: the agent targets
# Java 8, so anything 8+ is valid here, and JAVA_HOME is respected when set.
java_bin() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        printf '%s' "$JAVA_HOME/bin/java"
    else
        command -v java
    fi
}

# Skips the whole check with a message and exit 0 when a tool it needs is absent. Used by the Maven
# check: no mvn on the machine is a reason not to run it, not a failure of the agent.
require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        printf 'SKIP: %s is not on PATH (%s)\n' "$1" "$2"
        exit 0
    fi
}
