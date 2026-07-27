#!/usr/bin/env python3
"""Stand-in for the IDE end of the BeanShell debug transport.

Speaks protocol 2, the same wire format as `BshDebugProcess.readLoop`. The agent in
the debugged JVM connects back over a TCP socket and, at every statement it reports,
sends

    byte 0x10 EVT_STOPPED
    int  line                                    (big-endian throughout)
    int  callDepth
    int  frameCount
    (utf name, utf sourceFile, int line) * frameCount    innermost frame first

then blocks until it is resumed, answering anything asked in the meantime. The
return channel carries:

    0x01                                                   resume
    0x02  int count  (utf file, int line)*                 set breakpoints
    0x03  byte mode                                        set run mode, 0 = running
    0x04  int frameId                                      scopes of a frame
    0x05  int handle                                       children of a value
    0x06  int frameId, utf expression                      evaluate
    0x07  int frameId, int handle, utf name, utf expr      set a variable

Each request is answered before the loop goes back to waiting, so a reply is always
the next thing on the wire and no request ids are needed.

Variables are pulled rather than pushed: a stop reports only the stack, and this
script then asks for frame 0's scopes and their variables the way an open variables
panel would. A value with a non-zero child handle can be expanded further.

Use it to prove — without the live XDebug UI — that a script is instrumented,
connects, and reports the expected lines, frames and values.

Usage:
    python3 tools/mock-ide.py <port> [options]

      --breakpoints file.bsh:25,other.bsh:43    push a breakpoint set
      --eval 'x + 1,greeter.name'               evaluate at every stop
      --set count=99                            assign in frame 0 at every stop
      --expand                                  open every expandable value one level

An agent that has been given a breakpoint set stops reporting every statement and
speaks up only where a breakpoint matches, which is what makes a loop usable. Note
that the first statement is always reported: the agent cannot know the breakpoints
before the connection exists.

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
import argparse
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
CMD_EVALUATE = 0x06
CMD_SET_VARIABLE = 0x07

EVT_STOPPED = 0x10
EVT_SCOPES = 0x11
EVT_VARIABLES = 0x12
EVT_EVALUATED = 0x13
EVT_VARIABLE_SET = 0x14

NO_HANDLE = 0


def rbyte(f):
    return readn(f, 1)[0]


def rint(f):
    return struct.unpack(">i", readn(f, 4))[0]


def rutf(f):
    ln = struct.unpack(">H", readn(f, 2))[0]
    return readn(f, ln).decode("utf-8", "replace")


def wutf(text):
    """Java DataOutputStream.writeUTF: a 2-byte length followed by the bytes."""
    encoded = text.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def expect(f, event, what):
    got = rbyte(f)
    if got != event:
        sys.exit(f"[mock-ide] expected {what} (0x{event:02x}), got 0x{got:02x}")


def request_scopes(conn, f, frame_id):
    """Ask for one frame's scopes. The reply is the next thing on the wire: the agent serves
    requests from inside the same loop that waits for RESUME, so nothing can interleave."""
    conn.sendall(struct.pack(">Bi", CMD_SCOPES, frame_id))
    expect(f, EVT_SCOPES, "EVT_SCOPES")
    return [(rutf(f), rint(f)) for _ in range(rint(f))]


def request_variables(conn, f, handle):
    """The children of one handle, as a list of (name, value, type, childHandle)."""
    conn.sendall(struct.pack(">Bi", CMD_VARIABLES, handle))
    expect(f, EVT_VARIABLES, "EVT_VARIABLES")
    return [(rutf(f), rutf(f), rutf(f), rint(f)) for _ in range(rint(f))]


def read_outcome(f):
    """The shared reply shape of evaluate and set-variable: ok, then value/type/handle."""
    ok = rbyte(f) != 0
    value, type_name, child = rutf(f), rutf(f), rint(f)
    return ok, value, type_name, child


def request_evaluate(conn, f, frame_id, expression):
    conn.sendall(struct.pack(">Bi", CMD_EVALUATE, frame_id) + wutf(expression))
    expect(f, EVT_EVALUATED, "EVT_EVALUATED")
    return read_outcome(f)


def request_set_variable(conn, f, frame_id, handle, name, expression):
    conn.sendall(
        struct.pack(">Bii", CMD_SET_VARIABLE, frame_id, handle) + wutf(name) + wutf(expression)
    )
    expect(f, EVT_VARIABLE_SET, "EVT_VARIABLE_SET")
    return read_outcome(f)


def render(name, value, type_name, child):
    suffix = f" ({type_name}" + (f", +{child})" if child else ")") if type_name else ""
    return f"{name} = {value}{suffix}"


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


def report_frame(conn, f, args):
    """Inspect frame 0 the way an open variables panel does, plus whatever was asked for."""
    for scope, handle in request_scopes(conn, f, 0):
        variables = request_variables(conn, f, handle)
        print(f"[mock-ide]   {scope}:", flush=True)
        for name, value, type_name, child in variables:
            print(f"[mock-ide]     {render(name, value, type_name, child)}", flush=True)
            if args.expand and child != NO_HANDLE:
                for entry in request_variables(conn, f, child):
                    print(f"[mock-ide]       {render(*entry)}", flush=True)

        if args.set:
            name, _, expression = args.set.partition("=")
            ok, value, type_name, child = request_set_variable(conn, f, 0, handle, name, expression)
            verdict = render(name, value, type_name, child) if ok else f"REFUSED: {value}"
            print(f"[mock-ide]   set {name} = {expression} -> {verdict}", flush=True)

    for expression in args.eval:
        ok, value, type_name, child = request_evaluate(conn, f, 0, expression)
        verdict = render(expression, value, type_name, child) if ok else f"FAILED: {value}"
        print(f"[mock-ide]   eval {verdict}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="IDE stand-in for the BeanShell debug transport")
    parser.add_argument("port", type=int)
    parser.add_argument("--breakpoints", default="", help="file.bsh:line,...")
    parser.add_argument("--eval", default="", help="expressions to evaluate at every stop")
    parser.add_argument("--set", default="", help="name=expression to assign at every stop")
    parser.add_argument("--expand", action="store_true", help="open every expandable value one level")
    args = parser.parse_args()
    breakpoints = parse_breakpoints(args.breakpoints) if args.breakpoints else []
    args.eval = [e for e in (x.strip() for x in args.eval.split(",")) if e]

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(1)
    print(f"[mock-ide] listening on {args.port}", flush=True)

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

            report_frame(conn, f, args)

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
