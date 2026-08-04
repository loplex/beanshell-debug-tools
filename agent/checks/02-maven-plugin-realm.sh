#!/usr/bin/env bash
#
# The claim this repository rests on: the agent reaches BeanShell *inside a Maven plugin's own
# classloader*, and the source-prefix filter keeps the session out of BeanShell's built-in commands.
#
# It is the motivating case for the whole design -- the hook lives on the bootstrap classpath for no
# other reason than this -- and it cannot be tested from Gradle: it needs a real `mvn` process with a
# real plugin realm. Hence a script.
#
# Uses the repository's own sample poms, so the fixtures and the check cannot drift apart.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

banner "02 — the agent reaches BeanShell inside a Maven plugin realm"

require_command mvn "needed to exercise a real plugin realm"
need_paths

SAMPLES="$REPO_ROOT/plugin/samples/maven"

# --- build-helper-maven-plugin, <source> ------------------------------------------------------
#
# Its mojo trims the XML text before evaluating, so the reported name has no leading blanks and the
# script's first line is line 1. That is the "trimmed" reading BshMavenDebugSupport offers first.

POM="$SAMPLES/build-helper/pom.xml"
if [[ ! -f "$POM" ]]; then
    fail "sample pom present" "missing: $POM"
    finish
    exit
fi

MAVEN_OPTS="-javaagent:$AGENT_JAR -Dbsh.debug.trace=1" \
    mvn -o -q -f "$POM" validate > "$CHECK_TMP/bh.txt" 2>&1
grep 'bsh-agent' "$CHECK_TMP/bh.txt" > "$CHECK_TMP/bh-agent.txt" || true

assert_contains "$CHECK_TMP/bh-agent.txt" 'src=inline evaluation of: ``name = project.artifactId + ":" + project.version;' \
    "build-helper: the inline <source> is instrumented inside the plugin realm" "$CHECK_TMP/bh.txt"
assert_contains "$CHECK_TMP/bh-agent.txt" 'line=1 src=inline evaluation of' \
    "build-helper: lines are snippet-relative (first statement is line 1)" "$CHECK_TMP/bh.txt"
assert_contains "$CHECK_TMP/bh-agent.txt" 'line=3 src=inline evaluation of' \
    "build-helper: the third statement reports line 3" "$CHECK_TMP/bh.txt"

# --- the source-prefix filter -----------------------------------------------------------------
#
# Built here the way the plugin builds it: the trimmed script text, newlines flattened, first 60
# chars. If this stops matching, Maven sessions go quiet -- so the check computes the prefix by the
# same rule rather than hard-coding a string that could drift from the code.

python3 - "$POM" > "$CHECK_TMP/prefixes.txt" <<'PY'
import re, sys
pom = open(sys.argv[1], encoding='utf-8').read()
body = re.search(r'<!\[CDATA\[(.*?)\]\]>', pom, re.S).group(1)
flat = body.strip().replace('\n', ' ').replace('\r', ' ')
print("inline evaluation of: ``" + flat[:60])
PY

MAVEN_OPTS="-javaagent:$AGENT_JAR -Dbsh.debug.trace=1 -Dbsh.debug.sources.file=$CHECK_TMP/prefixes.txt" \
    mvn -o -q -f "$POM" validate > "$CHECK_TMP/filtered.txt" 2>&1
grep 'bsh-agent' "$CHECK_TMP/filtered.txt" > "$CHECK_TMP/filtered-agent.txt" || true

assert_contains "$CHECK_TMP/filtered-agent.txt" 'src=inline evaluation of' \
    "filter: a prefix computed by the production rule still matches the script" "$CHECK_TMP/filtered.txt"
assert_not_contains "$CHECK_TMP/filtered-agent.txt" 'print.bsh' \
    "filter: BeanShell's own print.bsh is excluded"

# Without the filter, print.bsh *should* show up -- otherwise the assertion above proves nothing.
if grep -q 'print.bsh' "$CHECK_TMP/bh-agent.txt"; then
    pass "filter: print.bsh does appear unfiltered, so the exclusion above is meaningful"
else
    # Not a failure of the agent: this pom may simply not call print().
    printf '  \033[33mNOTE\033[0m this pom does not reach print.bsh, so the filter test is weaker here\n'
fi

# --- maven-enforcer-plugin, <condition> -------------------------------------------------------
#
# A different plugin, a different tag, and an expression rather than statements -- worth covering
# because the enforcer is the case the agent was built for.

ENFORCER="$SAMPLES/enforcer/pom.xml"
if [[ -f "$ENFORCER" ]]; then
    MAVEN_OPTS="-javaagent:$AGENT_JAR -Dbsh.debug.trace=1" \
        mvn -o -q -f "$ENFORCER" validate > "$CHECK_TMP/enf.txt" 2>&1
    grep 'bsh-agent' "$CHECK_TMP/enf.txt" > "$CHECK_TMP/enf-agent.txt" || true
    assert_contains "$CHECK_TMP/enf-agent.txt" 'src=inline evaluation of' \
        "enforcer: the inline <condition> is instrumented too" "$CHECK_TMP/enf.txt"
else
    printf '  \033[33mNOTE\033[0m no enforcer sample at %s, skipping that half\n' "$ENFORCER"
fi

finish
