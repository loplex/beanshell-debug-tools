#!/usr/bin/env bash
#
# What BeanShell calls a script it was handed as a *string*, and how it numbers its lines.
#
# This is load-bearing for the Maven path. An inline <script> never becomes a file, so the agent has
# no file name to report and the IDE has nothing to match on -- except the synthetic name BeanShell
# invents from the script's own text:
#
#     inline evaluation of: ``<script, newlines flattened, cut at 80 chars + " . . . ">''
#
# BshMavenDebugSupport.beanShellSourceName reproduces that rule and the production code matches a
# 60-character prefix of it. If a BeanShell upgrade changed the wording, the elision, or the appended
# ';', Maven debugging would stop resolving scripts and the failure would be a silent "no
# breakpoints hit". This check is what makes that a red line instead.
#
# It also pins the *line numbering*: BeanShell counts from the first line of the text it was handed,
# so the IDE's snippet-to-pom map is built on that and nothing else.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

banner "01 — the synthetic source name and line numbering of eval(String)"

need_paths
JAVA="$(java_bin)"

cat > "$CHECK_TMP/InlineProbe.java" <<'EOF'
import bsh.Interpreter;

/** Mimics a Maven plugin evaluating an inline <script>: eval() of a multi-line String. */
public class InlineProbe {
    public static void main(String[] args) throws Exception {
        String script = "int a = 1;\nint b = 2;\nprint(\"sum=\" + (a + b));\n";
        new Interpreter().eval(script);
    }
}
EOF

"${JAVA%/java}/javac" -cp "$BSH_CLASSPATH" -d "$CHECK_TMP" "$CHECK_TMP/InlineProbe.java" \
    2>"$CHECK_TMP/javac.err" || { cat "$CHECK_TMP/javac.err" >&2; exit 1; }

# bsh.debug.trace reports to stderr and needs no listener, which is what makes this a one-process check.
"$JAVA" -javaagent:"$AGENT_JAR" -Dbsh.debug.trace=1 \
    -cp "$BSH_CLASSPATH:$CHECK_TMP" InlineProbe > "$CHECK_TMP/out.txt" 2>&1

grep 'bsh-agent' "$CHECK_TMP/out.txt" > "$CHECK_TMP/agent.txt" || true

assert_contains "$CHECK_TMP/agent.txt" 'src=inline evaluation of: ``' \
    "the source name still starts with BeanShell's 'inline evaluation of:' wording"

# Newlines must appear as spaces: the whole reason a prefix match works is that the name is one line.
assert_contains "$CHECK_TMP/agent.txt" 'inline evaluation of: ``int a = 1; int b = 2;' \
    "newlines in the script are flattened to spaces in the name"

# Lines are relative to the string, so statement 1 of the snippet is line 1 -- not an offset into
# any file. The pom line map depends on exactly this.
assert_contains "$CHECK_TMP/agent.txt" 'line=1 src=inline evaluation of' \
    "the first statement of the snippet is line 1"
assert_contains "$CHECK_TMP/agent.txt" 'line=3 src=inline evaluation of' \
    "the third statement of the snippet is line 3"

assert_contains "$CHECK_TMP/out.txt" 'sum=3' "the script still produced its own output"

finish
