#!/usr/bin/env bash
#
# Suspend: All — rounding up the threads that did *not* hit the breakpoint.
#
# An instrumenting agent cannot freeze a thread from outside; a thread only ever stops where it calls
# the hook. So "suspend all" is built the other way round: while one thread is suspended under a
# Suspend: All breakpoint, every other thread reports its **next statement** even though no breakpoint
# sits there, and the IDE holds each one as it arrives.
#
# What this check proves is exactly that difference — a thread stopping at a line that has no
# breakpoint on it. Anything less would also be true of plain per-thread suspension.
#
# The honest limit, which this cannot check and no implementation can remove: a thread that is
# sleeping, blocked, or deep inside Java code reports nothing until it next reaches a script
# statement, so it keeps running until then.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

banner "06 — Suspend: All rounds up threads that hit no breakpoint"

need_paths
JAVA="$(java_bin)"
PORT=$((20000 + RANDOM % 20000))

# Two threads running *different* code, so the breakpoint belongs to one of them only. That is what
# makes the proof unambiguous: thread B has no breakpoint anywhere in its own loop, so if it ever
# reports, it can only be because the round-up told it to.
cat > "$CHECK_TMP/two.bsh" <<'EOF'
taskA() {
    run() {
        for (i = 0; i < 6; i++) {
            aLine = "A" + i;
            Thread.sleep(2);
        }
        return null;
    }
    return this;
}
taskB() {
    run() {
        for (j = 0; j < 6; j++) {
            bLine = "B" + j;
            Thread.sleep(2);
        }
        return null;
    }
    return this;
}
ta = new Thread((Runnable) taskA(), "bsh-A");
tb = new Thread((Runnable) taskB(), "bsh-B");
ta.start();
tb.start();
ta.join();
tb.join();
print("two.bsh done");
EOF

FIRST=$(grep -n 'aLine = ' "$CHECK_TMP/two.bsh" | cut -d: -f1)
SECOND=$(grep -n 'bLine = ' "$CHECK_TMP/two.bsh" | cut -d: -f1)
printf '  (breakpoint only on line %s, in thread A; line %s in thread B has none)\n' "$FIRST" "$SECOND"

python3 "$REPO_ROOT/plugin/tools/mock-ide.py" "$PORT" \
    --breakpoints "two.bsh:$FIRST" --catch-all --hold-stops 2 > "$CHECK_TMP/ide.txt" 2>&1 &
IDE_PID=$!
for _ in $(seq 50); do
    grep -q 'listening on' "$CHECK_TMP/ide.txt" 2>/dev/null && break
    sleep 0.1
done

(cd "$CHECK_TMP" && "$JAVA" -javaagent:"$AGENT_JAR" -Dbsh.debug.port="$PORT" \
    -Dbsh.debug.sources=two.bsh -cp "$BSH_CLASSPATH" bsh.Interpreter two.bsh) \
    > "$CHECK_TMP/script.txt" 2>&1
wait "$IDE_PID" 2>/dev/null || true

assert_contains "$CHECK_TMP/ide.txt" 'agent connected' "the agent connected"
assert_contains "$CHECK_TMP/script.txt" 'two.bsh done' \
    "the script ran to completion -- nothing was left parked"

assert_contains "$CHECK_TMP/ide.txt" 'catch-all on' "catch-all was turned on at the breakpoint"
assert_contains "$CHECK_TMP/ide.txt" 'catch-all off' "and turned off again on resume"

# The proof: a stop reported at the line that carries no breakpoint.
if grep -qE "STOPPED .* line=$SECOND " "$CHECK_TMP/ide.txt"; then
    pass "thread B stopped at line $SECOND, which has no breakpoint -- it was rounded up"
else
    fail "a thread stopped at the line with no breakpoint" \
        "only these lines were reported: $(grep -oE 'line=[0-9]+' "$CHECK_TMP/ide.txt" | sort -u | tr '\n' ' ')"
fi

# And both worker threads took part, so the round-up is not one thread's accident.
assert_contains "$CHECK_TMP/ide.txt" 'bsh-A' "thread bsh-A reported"
assert_contains "$CHECK_TMP/ide.txt" 'bsh-B' "thread bsh-B reported"

finish
