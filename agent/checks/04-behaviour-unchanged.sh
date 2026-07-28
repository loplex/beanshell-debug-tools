#!/usr/bin/env bash
#
# The agent must not change what a script does. This is the check that pins that down.
#
# Runs the embedded-host fixtures twice -- uninstrumented and under the agent -- and compares. The
# comparison has to allow exactly three differences, all documented in agent/samples/README.md, and
# allowing them by pattern rather than by eye is the point of automating it:
#
#   * identity hash codes shift (Point@279f2327 -> Point@30f39991), because initialising the hook on
#     the interpreter thread advances that thread's identity-hash sequence;
#   * the interleaving of the two threads in scenario 5 is not deterministic in either run;
#   * bsh.NameSpace@... addresses, for the same reason as the first.
#
# Anything else differing means the agent changed behaviour, which is a bug however useful the
# debugger is.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

banner "04 — behaviour is unchanged with the agent attached"

if ! "$GRADLEW" -q -p "$REPO_ROOT" :agent:samples:runHost > "$CHECK_TMP/plain.txt" 2>"$CHECK_TMP/plain.err"; then
    fail "the uninstrumented fixtures run" "$(tail -3 "$CHECK_TMP/plain.err")"
    finish
    exit
fi
if ! "$GRADLEW" -q -p "$REPO_ROOT" :agent:samples:runHostWithAgent > "$CHECK_TMP/agent.txt" 2>"$CHECK_TMP/agent.err"; then
    fail "the instrumented fixtures run" "$(tail -3 "$CHECK_TMP/agent.err")"
    finish
    exit
fi

pass "both runs completed"

# Normalise the three legitimate differences away, then require equality. Sorting the thread-5 lines
# is what makes the interleaving irrelevant without hiding a missing line: a dropped or extra line
# still changes the sorted text.
normalise() {
    sed -E \
        -e 's/@[0-9a-f]{6,}/@HASH/g' \
        -e 's/bsh\.NameSpace: [^ ]+ \(bsh\.NameSpace@HASH\)/bsh.NameSpace@HASH/g' \
        "$1" | LC_ALL=C sort
}

normalise "$CHECK_TMP/plain.txt" > "$CHECK_TMP/plain.norm"
normalise "$CHECK_TMP/agent.txt" > "$CHECK_TMP/agent.norm"

if diff -q "$CHECK_TMP/plain.norm" "$CHECK_TMP/agent.norm" >/dev/null; then
    pass "output is identical once identity hashes and thread interleaving are normalised"
else
    fail "output differs beyond the three documented differences" \
        "$(diff "$CHECK_TMP/plain.norm" "$CHECK_TMP/agent.norm" | head -20)"
fi

# A weaker but independent assertion: the same number of lines, which catches a fixture that silently
# stopped early under the agent even if normalisation were too generous.
plain_lines=$(wc -l < "$CHECK_TMP/plain.txt")
agent_lines=$(wc -l < "$CHECK_TMP/agent.txt")
assert_equals "$plain_lines" "$agent_lines" "both runs produced the same number of output lines"

finish
