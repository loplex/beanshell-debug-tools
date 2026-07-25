#!/usr/bin/env python3
"""Stand-in for the IDE end of the BeanShell debug transport.

Speaks the same wire protocol as BshDebugProcess.readLoop: the instrumented
script's BshDebugAgent connects back over a TCP socket and, for every step(),
sends

    int  line          (big-endian)
    int  callDepth
    int  varCount
    (utf name, utf value) * varCount

then blocks reading one byte to resume. This script accepts one connection,
prints every frame, and always replies with 0x01 (resume) so the script runs
to completion. Use it to prove — without the live XDebug UI — that a script is
actually instrumented, connects, and reports the expected host lines and vars.

Usage:
    python3 tools/mock-ide.py <port>

Then launch a real Maven build wired to this port, e.g. debugging an inline
enforcer <condition>:

    EXT=build/libs/bsh-maven-ext.jar
    CB=build/libs/intellij-beanshell-<version>-base.jar   # carries BshDebugAgent
    mvn -Dmaven.ext.class.path="$EXT" \
        -Dbsh.debug.manifest="$PWD/manifest.txt" \
        -Dbsh.debug.callback.jar="$CB" \
        -Dbsh.debug.port=<port> \
        validate

The manifest lists one tab-separated line per inline script:
    artifactId<TAB>tag<TAB>originalScriptFile<TAB>instrumentedScriptFile
No "agent connected" line means the rewriter did not substitute the script
(e.g. a content mismatch) — the script ran uninstrumented.
Optionally pushes a breakpoint set, to exercise agent-side filtering:

    python3 tools/mock-ide.py <port> --breakpoints script.bsh:25,script.bsh:43

The return channel carries commands as well as the plain resume byte:

    0x01                                        resume (the original protocol)
    0x02  int count  (utf file, int line)*      set breakpoints
    0x03  byte mode                             set run mode, 0 = running

An agent that has been given a breakpoint set stops reporting every statement
and speaks up only where a breakpoint matches, which is what makes a loop
usable. Until it receives 0x02 it reports everything, so an IDE that only ever
replies 0x01 behaves exactly as before. Note that the first statement is always
reported: the agent cannot know the breakpoints before the connection exists.
"""
import socket
import struct
import sys


def readn(f, n):
    b = b""
    while len(b) < n:
        c = f.read(n - len(b))
        if not c:
            raise EOFError
        b += c
    return b


def rint(f):
    return struct.unpack(">i", readn(f, 4))[0]


def rutf(f):
    ln = struct.unpack(">H", readn(f, 2))[0]
    return readn(f, ln).decode("utf-8", "replace")


def wutf(text):
    """Java DataOutputStream.writeUTF: a 2-byte length followed by the bytes."""
    encoded = text.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def parse_breakpoints(spec):
    """`file.bsh:25,other.bsh:43` -> [("file.bsh", 25), ("other.bsh", 43)]."""
    breakpoints = []
    for item in spec.split(","):
        item = item.strip()
        if not item:
            continue
        path, _, line = item.rpartition(":")
        if not path:
            sys.exit(f"bad breakpoint {item!r}, expected file.bsh:line")
        breakpoints.append((path, int(line)))
    return breakpoints


def set_breakpoints_command(breakpoints):
    message = struct.pack(">B", 0x02) + struct.pack(">i", len(breakpoints))
    for path, line in breakpoints:
        message += wutf(path) + struct.pack(">i", line)
    # Also declare "running", so the agent filters instead of reporting every statement.
    return message + struct.pack(">BB", 0x03, 0)


def main():
    if len(sys.argv) not in (2, 4) or (len(sys.argv) == 4 and sys.argv[2] != "--breakpoints"):
        sys.exit("usage: mock-ide.py <port> [--breakpoints file.bsh:line,...]")
    port = int(sys.argv[1])
    breakpoints = parse_breakpoints(sys.argv[3]) if len(sys.argv) == 4 else []

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    print(f"[mock-ide] listening on {port}", flush=True)

    conn, _ = srv.accept()
    print("[mock-ide] agent connected", flush=True)
    f = conn.makefile("rb")
    configured = not breakpoints
    steps = 0
    try:
        while True:
            line = rint(f)
            depth = rint(f)
            count = rint(f)
            variables = {}
            for _ in range(count):
                name = rutf(f)
                variables[name] = rutf(f)
            steps += 1
            print(f"[mock-ide] STEP line={line} depth={depth} vars={variables}", flush=True)
            if not configured:
                # Only possible now: the agent opens the connection on its first report, so
                # that first statement is always seen unfiltered.
                conn.sendall(set_breakpoints_command(breakpoints))
                configured = True
                print(f"[mock-ide] pushed {len(breakpoints)} breakpoint(s)", flush=True)
            conn.sendall(b"\x01")  # resume
    except EOFError:
        print(f"[mock-ide] agent disconnected after {steps} step(s) (script done)", flush=True)


if __name__ == "__main__":
    main()
