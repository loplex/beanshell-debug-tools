#!/usr/bin/env bash
#
# Two script threads, told apart and suspended independently.
#
# This is the check the thread work exists for, and it is the one that could not pass before it:
# under protocol 2 the suspended thread was itself the socket reader, so a second thread reaching a
# breakpoint had nobody to report to -- it blocked on a lock the first thread held until that one was
# resumed. Two threads suspended at the same time was structurally impossible, not merely unsupported.
#
# Uses the repository's own threads fixture, whose BP:5 line is reached six times by two threads
# (bsh-X and bsh-Y). --hold-stops 2 keeps the first two suspended rather than resuming each in turn,
# which is the only way to observe both parked at once.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

banner "05 — two script threads suspended at once, each with its own state"

need_paths
JAVA="$(java_bin)"
PORT=$((20000 + RANDOM % 20000))

FIXTURE="$REPO_ROOT/agent/samples/scripts/06_callbacks_threads.bsh"
SCRIPT_DIR="$REPO_ROOT/agent/samples/scripts"

# The line the two spinning threads share. Located by its marker so the check survives the fixture
# being edited above it.
BP_LINE=$(grep -n 'print("  spin ' "$FIXTURE" | cut -d: -f1)
if [[ -z "$BP_LINE" ]]; then
    fail "found the BP:5 marker in the fixture" "grep found nothing"
    finish
    exit
fi
printf '  (breakpoint on %s:%s)\n' "$(basename "$FIXTURE")" "$BP_LINE"

python3 "$REPO_ROOT/plugin/tools/mock-ide.py" "$PORT" \
    --breakpoints "06_callbacks_threads.bsh:$BP_LINE" --hold-stops 2 > "$CHECK_TMP/ide.txt" 2>&1 &
IDE_PID=$!
for _ in $(seq 50); do
    grep -q 'listening on' "$CHECK_TMP/ide.txt" 2>/dev/null && break
    sleep 0.1
done

# Run from the fixture directory: the scripts source() each other by bare name.
(cd "$SCRIPT_DIR" && "$JAVA" -javaagent:"$AGENT_JAR" -Dbsh.debug.port="$PORT" \
    -Dbsh.debug.sources=06_callbacks_threads.bsh \
    -cp "$BSH_CLASSPATH" bsh.Interpreter 06_callbacks_threads.bsh) \
    > "$CHECK_TMP/script.txt" 2>&1
wait "$IDE_PID" 2>/dev/null || true

assert_contains "$CHECK_TMP/ide.txt" 'agent connected' "the agent connected"
assert_contains "$CHECK_TMP/script.txt" '06_callbacks_threads done' \
    "the script ran to completion -- no thread was left parked"

# --- distinct threads ---------------------------------------------------------------------------

# Every STOPPED carries a thread id and the JVM thread's name. The two spinning threads must appear
# as different ids, or the IDE has no way to keep their stacks apart.
grep -oE 'thread=[0-9]+ \([^)]*\)' "$CHECK_TMP/ide.txt" | sort -u > "$CHECK_TMP/threads.txt"
distinct=$(wc -l < "$CHECK_TMP/threads.txt")
if [[ "$distinct" -ge 3 ]]; then
    pass "at least three distinct threads reported (main plus the two workers): $distinct"
else
    fail "at least three distinct threads reported" "saw $distinct: $(tr '\n' ' ' < "$CHECK_TMP/threads.txt")"
fi

assert_contains "$CHECK_TMP/ide.txt" 'bsh-X' "the thread named bsh-X reported under its own name"
assert_contains "$CHECK_TMP/ide.txt" 'bsh-Y' "the thread named bsh-Y reported under its own name"

# --- two suspended at the same time --------------------------------------------------------------
#
# The hold is what proves independence: the first thread is still parked when the second reports,
# which under protocol 2 could not happen.

assert_contains "$CHECK_TMP/ide.txt" 'holding thread=' "the stand-in IDE held a thread suspended"
# The fixture hits the line six times, so the hold-and-release cycle repeats; what matters is that
# each cycle reached its quota of two, i.e. a second thread reported while the first was parked.
held=$(grep -c 'holding thread=.* (2/2)' "$CHECK_TMP/ide.txt")
if [[ "$held" -ge 1 ]]; then
    pass "a second thread reported while the first was still suspended (x$held)"
else
    fail "a second thread reported while the first was still suspended" \
        "no stop reached the 2/2 hold quota"
fi

# The held threads must be different ones -- holding the same thread twice would prove nothing.
grep -oE 'holding thread=[0-9]+' "$CHECK_TMP/ide.txt" | sort -u > "$CHECK_TMP/held.txt"
assert_equals "2" "$(wc -l < "$CHECK_TMP/held.txt")" \
    "the held threads are two distinct ones, so both were parked at once"

assert_contains "$CHECK_TMP/ide.txt" 'releasing held thread=' \
    "held threads were released again"

# --- per-thread state --------------------------------------------------------------------------
#
# Each spinning thread has its own `id` ("X" or "Y") in its own frame. Seeing both proves the frames
# and handle tables are per thread rather than shared -- a single shared table would have shown one
# thread's values under the other's stop.

assert_contains "$CHECK_TMP/ide.txt" 'id = X' "one suspended thread reported its own id = X"
assert_contains "$CHECK_TMP/ide.txt" 'id = Y' "the other reported its own id = Y"

finish
