#!/usr/bin/env python3
"""Stand-in for the IDE end of the BeanShell debug transport.

Speaks protocol 3, the same wire format as `BshDebugProcess.readLoop`. The full
specification is `docs/PROTOCOL.md`; in outline, the agent in the debugged JVM connects
back over a TCP socket and, at every statement it reports, sends

    byte 0x10 EVT_STOPPED
    int  threadId                                (big-endian throughout)
    utf  threadName
    int  line
    int  callDepth
    int  frameCount
    (utf name, utf sourceFile, int line) * frameCount    innermost frame first

then blocks *that thread* until it is resumed, answering anything asked about it in the
meantime. Other script threads keep running, and one of them may report while this one is
still suspended.

The return channel carries, each addressed to a thread and — where a reply is expected —
tagged with a request id the reply echoes:

    0x01  int threadId                                          resume
    0x02  int count (utf file, int line)*                       set breakpoints (global)
    0x03  int threadId, byte mode                               set run mode, 0 = running
    0x04  int threadId, int requestId, int frameId              scopes of a frame
    0x05  int threadId, int requestId, int handle               children of a value
    0x06  int threadId, int requestId, int frameId, utf expr    evaluate
    0x07  int threadId, int requestId, int frameId, int handle,
          utf name, utf expr                                    set a variable

**Why this script has a reader thread.** Under protocol 2 a reply was always the next
thing on the wire, so a request could be written and its answer read on the spot. That
stops being true once two script threads can be suspended: thread B may report a stop
between A's request and A's answer. So reads happen in one place and are demultiplexed —
stops onto a queue, replies to whoever is waiting on that request id. The plugin's own
`BshDebugProcess` has the same shape, for the same reason.

Variables are pulled rather than pushed: a stop reports only the stack, and this script
then asks for frame 0's scopes and their variables the way an open variables panel would.
A value with a non-zero child handle can be expanded further.

Use it to prove — without the live XDebug UI — that a script is instrumented, connects,
and reports the expected threads, lines, frames and values.

Usage:
    python3 tools/mock-ide.py <port> [options]

      --breakpoints file.bsh:25,other.bsh:43    push a breakpoint set
      --eval 'x + 1,greeter.name'               evaluate at every stop
      --set count=99                            assign in frame 0 at every stop
      --expand                                  open every expandable value one level
      --hold-stops N                            keep the first N threads suspended, to
                                                observe two at once, then release them

An agent that has been given a breakpoint set stops reporting every statement and speaks
up only where a breakpoint matches, which is what makes a loop usable. Note that the
first statement is always reported: the agent cannot know the breakpoints before the
connection exists.

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
import queue
import socket
import struct
import sys
import threading

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

REPLY_EVENTS = (EVT_SCOPES, EVT_VARIABLES, EVT_EVALUATED, EVT_VARIABLE_SET)


def readn(f, n):
    b = b""
    while len(b) < n:
        c = f.read(n - len(b))
        if not c:
            raise EOFError
        b += c
    return b


def rbyte(f):
    return readn(f, 1)[0]


def rint(f):
    return struct.unpack(">i", readn(f, 4))[0]


def rutf(f):
    ln = struct.unpack(">H", readn(f, 2))[0]
    return readn(f, ln).decode("utf-8", "replace")


def wutf(text):
    encoded = text.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


class Session:
    """Owns the socket: one reader thread in, serialised writes out.

    Every reply carries the request id it answers, so `request()` parks on a queue of its
    own instead of assuming it will be handed the next bytes to arrive.
    """

    def __init__(self, conn):
        self.conn = conn
        self.f = conn.makefile("rb")
        self.stops = queue.Queue()
        self._pending = {}
        self._next_id = 1
        self._lock = threading.Lock()
        self.error = None
        self.eof = threading.Event()
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _read_loop(self):
        try:
            while True:
                event = rbyte(self.f)
                if event == EVT_STOPPED:
                    stop = {
                        "thread": rint(self.f),
                        "thread_name": rutf(self.f),
                        "line": rint(self.f),
                        "depth": rint(self.f),
                    }
                    stop["frames"] = [
                        (rutf(self.f), rutf(self.f), rint(self.f)) for _ in range(rint(self.f))
                    ]
                    self.stops.put(stop)
                elif event in REPLY_EVENTS:
                    request_id = rint(self.f)
                    payload = self._read_reply(event)
                    with self._lock:
                        waiter = self._pending.pop(request_id, None)
                    if waiter is None:
                        print(f"[mock-ide] reply to unknown request {request_id}", flush=True)
                    else:
                        waiter.put((event, payload))
                else:
                    self.error = f"unexpected event 0x{event:02x}"
                    break
        except EOFError:
            pass
        finally:
            self.eof.set()
            # Unblock the main loop and anyone mid-request, so a departed agent cannot hang us.
            self.stops.put(None)
            with self._lock:
                waiters = list(self._pending.values())
                self._pending.clear()
            for waiter in waiters:
                waiter.put(None)

    def _read_reply(self, event):
        if event == EVT_SCOPES:
            return [(rutf(self.f), rint(self.f)) for _ in range(rint(self.f))]
        if event == EVT_VARIABLES:
            return [
                (rutf(self.f), rutf(self.f), rutf(self.f), rint(self.f)) for _ in range(rint(self.f))
            ]
        # evaluate / set-variable share one shape: ok, then value/type/handle.
        return (rbyte(self.f) != 0, rutf(self.f), rutf(self.f), rint(self.f))

    def send(self, payload):
        with self._lock:
            self.conn.sendall(payload)

    def request(self, event, build):
        """Sends a request and waits for the reply carrying its id."""
        waiter = queue.Queue(1)
        with self._lock:
            request_id = self._next_id
            self._next_id += 1
            self._pending[request_id] = waiter
            self.conn.sendall(build(request_id))
        answer = waiter.get()
        if answer is None:
            raise EOFError
        got, payload = answer
        if got != event:
            sys.exit(f"[mock-ide] expected 0x{event:02x} for request {request_id}, got 0x{got:02x}")
        return payload


def request_scopes(s, thread, frame_id):
    return s.request(EVT_SCOPES, lambda rid: struct.pack(">Biii", CMD_SCOPES, thread, rid, frame_id))


def request_variables(s, thread, handle):
    return s.request(
        EVT_VARIABLES, lambda rid: struct.pack(">Biii", CMD_VARIABLES, thread, rid, handle)
    )


def request_evaluate(s, thread, frame_id, expression):
    return s.request(
        EVT_EVALUATED,
        lambda rid: struct.pack(">Biii", CMD_EVALUATE, thread, rid, frame_id) + wutf(expression),
    )


def request_set_variable(s, thread, frame_id, handle, name, expression):
    return s.request(
        EVT_VARIABLE_SET,
        lambda rid: struct.pack(">Biiii", CMD_SET_VARIABLE, thread, rid, frame_id, handle)
        + wutf(name)
        + wutf(expression),
    )


def resume(s, thread):
    s.send(struct.pack(">Bi", CMD_RESUME, thread))


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
    return message


def report_frame(s, thread, args):
    """Inspect frame 0 the way an open variables panel does, plus whatever was asked for."""
    for scope, handle in request_scopes(s, thread, 0):
        variables = request_variables(s, thread, handle)
        print(f"[mock-ide]   {scope}:", flush=True)
        for name, value, type_name, child in variables:
            print(f"[mock-ide]     {render(name, value, type_name, child)}", flush=True)
            if args.expand and child != NO_HANDLE:
                for entry in request_variables(s, thread, child):
                    print(f"[mock-ide]       {render(*entry)}", flush=True)

        if args.set:
            name, _, expression = args.set.partition("=")
            ok, value, type_name, child = request_set_variable(
                s, thread, 0, handle, name, expression
            )
            verdict = render(name, value, type_name, child) if ok else f"REFUSED: {value}"
            print(f"[mock-ide]   set {name} = {expression} -> {verdict}", flush=True)

    for expression in args.eval:
        ok, value, type_name, child = request_evaluate(s, thread, 0, expression)
        verdict = render(expression, value, type_name, child) if ok else f"FAILED: {value}"
        print(f"[mock-ide]   eval {verdict}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="IDE stand-in for the BeanShell debug transport")
    parser.add_argument("port", type=int)
    parser.add_argument("--breakpoints", default="", help="file.bsh:line,...")
    parser.add_argument("--eval", default="", help="expressions to evaluate at every stop")
    parser.add_argument("--set", default="", help="name=expression to assign at every stop")
    parser.add_argument(
        "--expand", action="store_true", help="open every expandable value one level"
    )
    parser.add_argument(
        "--hold-stops",
        type=int,
        default=0,
        help="keep the first N suspended threads parked (to observe two at once), then release",
    )
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
    s = Session(conn)
    configured = not breakpoints
    steps = 0
    # Threads reported as stopped and not yet resumed. With --hold-stops this deliberately
    # grows, which is the only way to observe two script threads suspended at once.
    held = []
    while True:
        stop = s.stops.get()
        if stop is None:
            print(f"[mock-ide] agent disconnected after {steps} step(s) (script done)", flush=True)
            break
        steps += 1
        where = " < ".join(f"{name}:{ln}" for name, _src, ln in stop["frames"]) or "<no frames>"
        print(
            f"[mock-ide] STOPPED thread={stop['thread']} ({stop['thread_name']})"
            f" line={stop['line']} depth={stop['depth']} stack={where}",
            flush=True,
        )

        try:
            report_frame(s, stop["thread"], args)
        except EOFError:
            print("[mock-ide] agent went away mid-request", flush=True)
            break

        # Whether this stop is a breakpoint hit or merely the unfiltered first statement. Holding
        # matters only for the former: the first stop is always the main thread on the script's
        # first line, and parking that one stops the script before it ever starts a thread.
        at_breakpoint = configured

        if not configured:
            # Only possible now: the agent opens the connection on its first report, so that
            # first statement is always seen unfiltered.
            s.send(set_breakpoints_command(breakpoints))
            s.send(struct.pack(">Bib", CMD_SET_RUN_MODE, stop["thread"], 0))
            configured = True
            print(f"[mock-ide] pushed {len(breakpoints)} breakpoint(s)", flush=True)

        if at_breakpoint and len(held) < args.hold_stops:
            held.append(stop["thread"])
            print(
                f"[mock-ide] holding thread={stop['thread']} suspended"
                f" ({len(held)}/{args.hold_stops})",
                flush=True,
            )
            # Release as soon as the quota is met, not on some later stop: with only two script
            # threads, both being held means there is nobody left to produce that later stop, and
            # waiting for it would deadlock. By the time the Nth is held, all N were parked at
            # once -- which is the whole thing being demonstrated.
            if len(held) < args.hold_stops:
                continue
            for thread in held:
                print(f"[mock-ide] releasing held thread={thread}", flush=True)
                resume(s, thread)
            held = []
            continue

        resume(s, stop["thread"])

    if s.error:
        sys.exit(f"[mock-ide] {s.error}")


if __name__ == "__main__":
    main()
