#!/usr/bin/env python3
"""Check an instrumentation implementation against the markers in a .bsh sample.

`samples/instrumentation-boundaries.bsh` annotates every interesting line with
the position it represents:

    HOOK           a statement boundary -- both implementations act here
    NO-HOOK        inserting here would detach the following statement or split
                   an expression; neither implementation may act
    REWRITE-ONLY   the line-based rewriter legally inserts here, but the hook
                   attaches to the end of the preceding block and reports this
                   line anyway; the agent reports nothing, as no AST node starts
                   on the line
    AGENT-ONLY     a real statement the agent can stop on, where the rewriter must
                   not insert -- prefixing a brace-less body would detach it

Without those markers being checked they would rot, so this compares them against
what an implementation really does. Two targets:

    --target rewriter   (default) runs tools/bshInstrumenter.main.kts and looks at
                        which lines gained a step(...) prefix. Static, so every
                        marked line is checked whether or not it executes.

    --target agent      runs the script under the instrumenting agent in trace
                        mode and looks at which lines it reports. Dynamic, so it
                        can only confirm lines that actually execute; lines on
                        untaken branches are skipped and counted separately.

Where a line holds both a container and a brace-less body -- `if (t) note("x");`
is two positions on one line -- the marker written first applies, because that is
the one a line-based instrumenter can act on.

Blank and comment-only lines are ignored: prefixing a statement to a comment line
is a legal pure insertion, so doing it means nothing either way.

Usage:
    tools/check-instrumentation.py [sample.bsh] [--target rewriter|agent]
                                   [--bsh-classpath PATH] [--agent-jar PATH]

Exits non-zero on any mismatch.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

HOOK_CALL = "BshDebugAgent.step"
MARKER = re.compile(r"\b(NO-HOOK|REWRITE-ONLY|AGENT-ONLY|HOOK)\b")

REWRITER = ["./tools/bshInstrumenter.main.kts"]
AGENT_JAR_DIR = Path("../agent/instrument/build/libs")
AGENT_JAR_GLOB = "bsh-debug-agent-*.jar"
BUILD_AGENT_JAR = "../gradlew :agent:instrument:shadowJar"


def default_agent_jar():
    """The build stamps the repository version into the file name, so match on the prefix."""
    found = sorted(AGENT_JAR_DIR.glob(AGENT_JAR_GLOB))
    return str(found[-1]) if found else str(AGENT_JAR_DIR / AGENT_JAR_GLOB)


def is_code_line(text):
    stripped = text.strip()
    return bool(stripped) and not stripped.startswith("//")


def expected_markers(path):
    """line number (1-based) -> marker, for marked code lines only."""
    wanted = {}
    for number, text in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not is_code_line(text):
            continue
        found = MARKER.search(text.partition("//")[2])
        if found:
            wanted[number] = found.group(1)
    return wanted


def rewriter_hooks(path, command):
    """line -> True when the rewriter prefixed a hook call there."""
    with path.open("rb") as script:
        result = subprocess.run(command, stdin=script, capture_output=True)
    if result.returncode != 0:
        sys.exit(f"instrumenter failed ({result.returncode}):\n{result.stderr.decode(errors='replace')}")
    produced = result.stdout.decode("utf-8", "replace").splitlines()
    original = path.read_text(encoding="utf-8").splitlines()
    if len(produced) != len(original):
        # Line numbering must survive instrumentation: breakpoints are reported in
        # original coordinates, so an added or dropped line misplaces all of them.
        sys.exit(f"instrumenter changed the line count: {len(original)} -> {len(produced)}")
    return {n: HOOK_CALL in text for n, text in enumerate(produced, start=1)}


def agent_reported_lines(path, bsh_classpath, agent_jar):
    """Set of lines the agent reported, from a trace-mode run."""
    for label, value in (("--bsh-classpath", bsh_classpath), ("--agent-jar", agent_jar)):
        if not value:
            sys.exit(f"{label} is required for --target agent")
    if not Path(agent_jar).is_file():
        sys.exit(f"agent jar not found: {agent_jar}\nbuild it with: {BUILD_AGENT_JAR}")
    result = subprocess.run(
        ["java", f"-javaagent:{agent_jar}",
         "-Dbsh.debug.trace=1", f"-Dbsh.debug.sources={path.name}",
         "-cp", bsh_classpath, "bsh.Interpreter", str(path)],
        capture_output=True,
    )
    reported = set()
    for text in result.stderr.decode("utf-8", "replace").splitlines():
        if "[bsh-agent]" not in text:
            continue
        found = re.search(r"line=(\d+)", text)
        if found:
            reported.add(int(found.group(1)))
    if not reported:
        sys.exit("the agent reported nothing; is the script reaching the interpreter?\n"
                 + result.stderr.decode(errors="replace")[:800])
    return reported


def main():
    args = sys.argv[1:]

    def take(flag, default=None):
        if flag in args:
            at = args.index(flag)
            value = args[at + 1]
            del args[at:at + 2]
            return value
        return default

    target = take("--target", "rewriter")
    bsh_classpath = take("--bsh-classpath", os.environ.get("BSH_CLASSPATH"))
    agent_jar = take("--agent-jar", os.environ.get("BSH_AGENT_JAR") or default_agent_jar())
    if target not in ("rewriter", "agent"):
        sys.exit("--target must be rewriter or agent")

    sample = Path(args[0] if args else "samples/instrumentation-boundaries.bsh")
    if not sample.is_file():
        sys.exit(f"no such sample: {sample}")

    wanted = expected_markers(sample)
    if not wanted:
        sys.exit(f"{sample} carries no markers; nothing to check")
    source = sample.read_text(encoding="utf-8").splitlines()

    if target == "rewriter":
        hooked = rewriter_hooks(sample, REWRITER)
        # The rewriter is expected to act on both HOOK and REWRITE-ONLY positions.
        should_act = {"HOOK", "REWRITE-ONLY"}
        acted = lambda n: bool(hooked.get(n))
        unverifiable = set()
    else:
        reported = agent_reported_lines(sample, bsh_classpath, agent_jar)
        should_act = {"HOOK", "AGENT-ONLY"}
        acted = lambda n: n in reported
        # A dynamic run cannot prove a negative for code it never reached, and cannot
        # confirm a HOOK on an untaken branch either. Only "expected, absent" is
        # ambiguous, so collect those separately instead of failing on them.
        unverifiable = {n for n, m in wanted.items() if m in should_act and n not in reported}

    missing, spurious = [], []
    for number, marker in sorted(wanted.items()):
        expected = marker in should_act
        if expected and not acted(number):
            if number not in unverifiable:
                missing.append(number)
        elif not expected and acted(number):
            spurious.append(number)

    def report(title, numbers):
        print(f"{title}: {len(numbers)}")
        for n in numbers:
            print(f"  line {n:<4} {source[n - 1].strip()[:70]}")

    print(f"{sample}: {len(wanted)} marked lines, target={target}")
    if missing:
        report("MISSING (expected to act, did not)", missing)
    if spurious:
        report("SPURIOUS (expected not to act, did)", spurious)
    if unverifiable:
        print(f"not exercised by this run, unverified: {len(unverifiable)}")
        for number in sorted(unverifiable):
            print(f"  line {number:<4} {source[number - 1].strip()[:70]}")
    if not missing and not spurious:
        print("all checkable markers agree")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
