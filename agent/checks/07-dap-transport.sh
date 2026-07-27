#!/usr/bin/env bash
#
# The DAP transport: the same debugger, over the Debug Adapter Protocol.
#
# Selected with -Dbsh.debug.protocol=dap, and the agent then *listens* rather than connecting out,
# because a DAP client attaches to a debuggee that is already running. IntelliJ is unaffected: it
# keeps the native protocol, which check 03 and 05 cover.
#
# What this asserts is that a real DAP conversation works end to end -- the handshake in the right
# order, breakpoints honoured, a stack with more than one frame, both scopes, and an expression
# evaluated in the stopped frame. Between them those cover every translation the DAP channel makes,
# and each one has a specific way of going wrong:
#
#   * the handshake, because a client that never sees `initialized` will not configure anything;
#   * breakpoints, because DAP sends absolute paths where BeanShell may report a relative one;
#   * frame ids, because DAP quotes them back without saying which thread they belong to;
#   * scopes and variables, because a handle is DAP's variablesReference under another name.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

banner "07 — the DAP transport, end to end"

need_paths
JAVA="$(java_bin)"
PORT=$((20000 + RANDOM % 20000))

cat > "$CHECK_TMP/dap.bsh" <<'EOF'
total = 0;
compute(n) {
    doubled = n * 2;
    return doubled + total;
}
total = 5;
print("result=" + compute(7));
print("dap.bsh done");
EOF

BP=$(grep -n 'return doubled + total;' "$CHECK_TMP/dap.bsh" | cut -d: -f1)
CALL=$(grep -n 'print("result=' "$CHECK_TMP/dap.bsh" | cut -d: -f1)
printf '  (breakpoint on line %s, called from line %s)\n' "$BP" "$CALL"

# The agent listens, so it starts first and the client attaches. The script blocks on its first
# statement until the client has finished configuring -- without which a script this short would be
# over before a breakpoint could be set.
(cd "$CHECK_TMP" && "$JAVA" -javaagent:"$AGENT_JAR" \
    -Dbsh.debug.protocol=dap -Dbsh.debug.listen="$PORT" -Dbsh.debug.sources=dap.bsh \
    -cp "$BSH_CLASSPATH" bsh.Interpreter dap.bsh) > "$CHECK_TMP/script.txt" 2>&1 &
SCRIPT_PID=$!

for _ in $(seq 100); do
    grep -q 'DAP: listening' "$CHECK_TMP/script.txt" 2>/dev/null && break
    sleep 0.1
done

# Two stops: the first statement is always reported (the agent cannot know the breakpoints before the
# connection exists), then the breakpoint itself.
timeout 60 python3 "$REPO_ROOT/agent/checks/dap-client.py" "$PORT" \
    --source "$CHECK_TMP/dap.bsh" --breakpoints "$BP" --stops 2 \
    --evaluate 'doubled + 1' > "$CHECK_TMP/client.txt" 2>&1
wait "$SCRIPT_PID" 2>/dev/null || true

assert_contains "$CHECK_TMP/script.txt" 'DAP: client attached' "the agent accepted a DAP client"
assert_contains "$CHECK_TMP/script.txt" 'dap.bsh done' "the script ran to completion"
assert_contains "$CHECK_TMP/script.txt" 'result=19' "and produced its own correct output"

# --- the handshake, in order -------------------------------------------------------------------

assert_contains "$CHECK_TMP/client.txt" 'initialize ok' "initialize was answered with capabilities"
assert_contains "$CHECK_TMP/client.txt" 'supportsConfigurationDoneRequest' \
    "the capabilities include configurationDone, which the client needs to know about"
assert_contains "$CHECK_TMP/client.txt" 'initialized event received' \
    "the initialized event arrived -- without it a client configures nothing"
assert_contains "$CHECK_TMP/client.txt" "verified lines: [$BP]" \
    "setBreakpoints echoed the line back as verified"

# --- the breakpoint actually fired -------------------------------------------------------------
#
# The one assertion that would have caught the path-matching bug: a DAP client sends an absolute
# source path, while BeanShell reports whatever the script was launched with.

assert_contains "$CHECK_TMP/client.txt" "compute at dap.bsh:$BP" \
    "execution stopped inside compute() at the breakpoint line"

# --- the stack has depth, and frame ids resolve ------------------------------------------------

assert_contains "$CHECK_TMP/client.txt" "global at dap.bsh:$CALL" \
    "the caller frame is placed at its own call site, not at the current line"

# --- scopes and variables ----------------------------------------------------------------------

assert_contains "$CHECK_TMP/client.txt" 'scope Locals' "the Locals scope was offered"
assert_contains "$CHECK_TMP/client.txt" 'scope Global' "the Global scope was offered too"
assert_contains "$CHECK_TMP/client.txt" 'doubled = 14 [int]' \
    "a local reported its value and its unwrapped BeanShell type"

# --- evaluation in the stopped frame ------------------------------------------------------------

assert_contains "$CHECK_TMP/client.txt" "evaluate 'doubled + 1' -> 15" \
    "an expression evaluated in the frame's own scope"

finish
