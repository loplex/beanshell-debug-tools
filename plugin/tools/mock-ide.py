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


CMD_RESUME = 0x01
CMD_SET_BREAKPOINTS = 0x02
CMD_SET_RUN_MODE = 0x03
CMD_SCOPES = 0x04
CMD_VARIABLES = 0x05

EVT_STOPPED = 0x10
EVT_SCOPES = 0x11
EVT_VARIABLES = 0x12


def rbyte(f):
    return readn(f, 1)[0]


def rint(f):
    return struct.unpack(">i", readn(f, 4))[0]


def request_scopes(conn, f, frame_id):
    """Ask for one frame's scopes. The reply is the next thing on the wire: the agent serves
    requests from inside the same loop that waits for RESUME, so nothing can interleave."""
    conn.sendall(struct.pack(">Bi", CMD_SCOPES, frame_id))
    event = rbyte(f)
    if event != EVT_SCOPES:
        sys.exit(f"[mock-ide] expected EVT_SCOPES, got 0x{event:02x}")
    return [(rutf(f), rint(f)) for _ in range(rint(f))]


def request_variables(conn, f, handle):
    conn.sendall(struct.pack(">Bi", CMD_VARIABLES, handle))
    event = rbyte(f)
    if event != EVT_VARIABLES:
        sys.exit(f"[mock-ide] expected EVT_VARIABLES, got 0x{event:02x}")
    out = {}
    for _ in range(rint(f)):
        name, value, type_name, child = rutf(f), rutf(f), rutf(f), rint(f)
        out[name] = f"{value}" + (f" ({type_name}, +{child})" if child else "")
    return out


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
    message = struct.pack(">B", CMD_SET_BREAKPOINTS) + struct.pack(">i", len(breakpoints))
    for path, line in breakpoints:
        message += wutf(path) + struct.pack(">i", line)
    # Also declare "running", so the agent filters instead of reporting every statement.
    return message + struct.pack(">BB", CMD_SET_RUN_MODE, 0)


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
            event = rbyte(f)
            if event != EVT_STOPPED:
                sys.exit(f"[mock-ide] unexpected event 0x{event:02x} outside a request")
            line = rint(f)
            depth = rint(f)
            frames = [(rutf(f), rutf(f), rint(f)) for _ in range(rint(f))]
            steps += 1
            where = " < ".join(f"{name}:{ln}" for name, _src, ln in frames) or "<no frames>"
            print(f"[mock-ide] STOPPED line={line} depth={depth} stack={where}", flush=True)

            # Expand frame 0 the way an IDE does when the variables panel is open, which is also
            # the only way to see any variable at all now that they are fetched on demand.
            for scope, handle in request_scopes(conn, f, 0):
                print(f"[mock-ide]   {scope}: {request_variables(conn, f, handle)}", flush=True)

            if not configured:
                # Only possible now: the agent opens the connection on its first report, so
                # that first statement is always seen unfiltered.
                conn.sendall(set_breakpoints_command(breakpoints))
                configured = True
                print(f"[mock-ide] pushed {len(breakpoints)} breakpoint(s)", flush=True)
            conn.sendall(bytes([CMD_RESUME]))
    except EOFError:
        print(f"[mock-ide] agent disconnected after {steps} step(s) (script done)", flush=True)


if __name__ == "__main__":
    main()
