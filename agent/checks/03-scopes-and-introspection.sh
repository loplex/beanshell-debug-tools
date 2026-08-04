#!/usr/bin/env bash
#
# What the variables panel gets: the two scopes, and the expansion of BeanShell's own handles.
#
# Drives the real transport -- mock-ide.py is the IDE end -- so this covers the socket conversation
# as well as the values, which no unit test on either side does alone.
#
# The three things asserted here are easy to regress invisibly. A bsh.This must expand to the
# *namespace* it stands for rather than to its Java fields (that is what makes a closure's captured
# scope, a scripted instance's _bshThis... field, and a This handed back to Java all readable), Global
# must appear when stopped inside a method, since that is where a script's top-level state would
# otherwise become invisible, and a `for` loop's own namespace must appear as its own level rather
# than being lost inside Locals' or absorbed into Global.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

banner "03 — scopes and BeanShell-aware expansion, over the real transport"

need_paths
JAVA="$(java_bin)"
PORT=$((20000 + RANDOM % 20000))

cat > "$CHECK_TMP/introspect.bsh" <<'EOF'
globalCounter = 10;
makeCounter() {
    count = 0;
    bump() { count++; return count; }
    return this;
}
compute(n) {
    localVal = n * 2;
    return localVal + globalCounter;
}
c = makeCounter();
print("compute=" + compute(5));
EOF

# Breakpoint on the return inside compute(): a frame whose namespace is *not* the global one, which
# is the only situation where the Global scope is meant to appear.
python3 "$REPO_ROOT/plugin/tools/mock-ide.py" "$PORT" \
    --breakpoints introspect.bsh:9 --expand > "$CHECK_TMP/ide.txt" 2>&1 &
IDE_PID=$!

# Wait for the listener rather than sleeping a fixed amount: a fixed sleep is either flaky or slow.
for _ in $(seq 50); do
    grep -q 'listening on' "$CHECK_TMP/ide.txt" 2>/dev/null && break
    sleep 0.1
done

"$JAVA" -javaagent:"$AGENT_JAR" -Dbsh.debug.port="$PORT" -Dbsh.debug.sources=introspect.bsh \
    -cp "$BSH_CLASSPATH" bsh.Interpreter "$CHECK_TMP/introspect.bsh" \
    > "$CHECK_TMP/script.txt" 2>&1
wait "$IDE_PID" 2>/dev/null || true

assert_contains "$CHECK_TMP/ide.txt" 'agent connected' "the agent connected to the stand-in IDE"
assert_contains "$CHECK_TMP/script.txt" 'compute=20' "the script ran to completion and was unaffected"

# --- the stack ---------------------------------------------------------------------------------

assert_contains "$CHECK_TMP/ide.txt" 'stack=compute:9' \
    "the innermost frame is compute() at the breakpoint line"
assert_contains "$CHECK_TMP/ide.txt" '< global:12' \
    "the caller frame is placed at its own call site, not at the current line"

# --- the scopes --------------------------------------------------------------------------------

assert_contains "$CHECK_TMP/ide.txt" 'Locals:' "Locals is offered"
assert_contains "$CHECK_TMP/ide.txt" 'Global:' \
    "Global is offered while stopped inside a method"
assert_contains "$CHECK_TMP/ide.txt" 'localVal = 10 (int)' \
    "a local is reported with its BeanShell type unwrapped from bsh.Primitive"

# --- This expands as a namespace ---------------------------------------------------------------
#
# `c` holds the This returned by makeCounter(). Expanded, it must show that closure's own variable
# -- count -- and not the Java fields of bsh.XThis.

assert_contains "$CHECK_TMP/ide.txt" 'count = 0 (int)' \
    "a bsh.This expands to its namespace (the closure's captured 'count')"
assert_not_contains "$CHECK_TMP/ide.txt" 'declaringInterpreter' \
    "expanding a This does not leak bsh.XThis's own Java fields"

# --- a `for` loop's own namespace is its own scope level ------------------------------------------
#
# BSHForStatement wraps the loop in a BlockNameSpace of its own (holding the init variable) and the
# body runs in a second, subordinate BlockNameSpace -- two levels below the script's own Locals used
# to be flattened into one, hiding a typed loop variable declared in the `for`'s own init behind
# whichever scope's ancestor-walk happened to reach it first.

cat > "$CHECK_TMP/forloop.bsh" <<'EOF'
total = 0;
for (int i = 1; i <= 3; i++) {
    total += i;
    print("step " + i);
}
EOF

PORT2=$((20000 + RANDOM % 20000))
python3 "$REPO_ROOT/plugin/tools/mock-ide.py" "$PORT2" \
    --breakpoints forloop.bsh:3 --expand > "$CHECK_TMP/for-ide.txt" 2>&1 &
FOR_IDE_PID=$!

for _ in $(seq 50); do
    grep -q 'listening on' "$CHECK_TMP/for-ide.txt" 2>/dev/null && break
    sleep 0.1
done

"$JAVA" -javaagent:"$AGENT_JAR" -Dbsh.debug.port="$PORT2" -Dbsh.debug.sources=forloop.bsh \
    -cp "$BSH_CLASSPATH" bsh.Interpreter "$CHECK_TMP/forloop.bsh" \
    > "$CHECK_TMP/for-script.txt" 2>&1
wait "$FOR_IDE_PID" 2>/dev/null || true

assert_contains "$CHECK_TMP/for-ide.txt" 'Block:' \
    "the for-loop's own namespace is offered as a level of its own"
assert_contains "$CHECK_TMP/for-ide.txt" 'i = 1 (int)' \
    "the loop variable is visible, in the Block level it was actually declared in" "$CHECK_TMP/for-ide.txt"

# Global's own slice of the report, isolated so the assertion below cannot pass just because "i"
# legitimately appears a few lines up, under Block.
sed -n '/  Global:/,/^\[mock-ide\] STOPPED\|^\[mock-ide\] agent disconnected/p' "$CHECK_TMP/for-ide.txt" \
    > "$CHECK_TMP/for-global-only.txt"
assert_not_contains "$CHECK_TMP/for-global-only.txt" 'i = 1 (int)' \
    "the loop variable is not repeated in Global -- each level reports only its own" "$CHECK_TMP/for-ide.txt"

finish
