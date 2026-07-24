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


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: mock-ide.py <port>")
    port = int(sys.argv[1])

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    print(f"[mock-ide] listening on {port}", flush=True)

    conn, _ = srv.accept()
    print("[mock-ide] agent connected", flush=True)
    f = conn.makefile("rb")
    try:
        while True:
            line = rint(f)
            depth = rint(f)
            count = rint(f)
            variables = {}
            for _ in range(count):
                name = rutf(f)
                variables[name] = rutf(f)
            print(f"[mock-ide] STEP line={line} depth={depth} vars={variables}", flush=True)
            conn.sendall(b"\x01")  # resume
    except EOFError:
        print("[mock-ide] agent disconnected (script done)", flush=True)


if __name__ == "__main__":
    main()
