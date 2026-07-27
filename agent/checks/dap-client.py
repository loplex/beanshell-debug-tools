#!/usr/bin/env python3
"""A minimal DAP client, for driving the agent's DAP transport without an editor.

What `mock-ide.py` is to the native protocol, this is to DAP: enough of a client to prove the
agent speaks the protocol, and small enough to read. It attaches to a debuggee already
running under `-Dbsh.debug.protocol=dap -Dbsh.debug.listen=PORT`, which is the direction DAP
clients expect — the adapter is the server.

The handshake is the part worth knowing, because getting it out of order is the usual reason a
DAP session silently does nothing:

    -> initialize            capabilities exchange
    <- (response)            what the adapter can do
    <- initialized (event)   *now* the client may configure
    -> setBreakpoints        per source, authoritative for that source
    -> configurationDone     "I am finished; you may run"
    <- stopped (event)       a thread suspended
    -> threads / stackTrace / scopes / variables / evaluate
    -> continue

Usage:
    python3 agent/checks/dap-client.py <port> --source FILE --breakpoints 12,15 [--stops N]
                                       [--evaluate 'expr'] [--set name=value] [--json]

Prints one line per protocol step, which is what the checks assert on. `--json` additionally
dumps every message, for when the disagreement is about the wire rather than the behaviour.
"""
import argparse
import json
import socket
import sys
import threading
import queue


class DapConnection:
    """Content-Length framed JSON, with the reads demultiplexed by request seq.

    A reader thread is not optional here: DAP interleaves events with responses freely, so a
    client that read synchronously after each request would eventually consume a `stopped`
    event where it expected its own answer.
    """

    def __init__(self, sock, log_json=False):
        self.sock = sock
        self.file = sock.makefile("rb")
        self.log_json = log_json
        self.events = queue.Queue()
        self._pending = {}
        self._seq = 0
        self._lock = threading.Lock()
        self.closed = threading.Event()
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _read_loop(self):
        try:
            while True:
                message = self._read_message()
                if message is None:
                    break
                if self.log_json:
                    print(f"[dap] <- {json.dumps(message)}", flush=True)
                if message.get("type") == "event":
                    self.events.put(message)
                elif message.get("type") == "response":
                    waiter = None
                    with self._lock:
                        waiter = self._pending.pop(message.get("request_seq"), None)
                    if waiter is not None:
                        waiter.put(message)
        finally:
            self.closed.set()
            self.events.put(None)
            with self._lock:
                waiters = list(self._pending.values())
                self._pending.clear()
            for waiter in waiters:
                waiter.put(None)

    def _read_message(self):
        length = None
        while True:
            line = self.file.readline()
            if not line:
                return None
            line = line.decode("utf-8").strip()
            if not line:
                break
            if line.lower().startswith("content-length:"):
                length = int(line.split(":", 1)[1].strip())
        if length is None:
            return None
        body = b""
        while len(body) < length:
            chunk = self.file.read(length - len(body))
            if not chunk:
                return None
            body += chunk
        return json.loads(body.decode("utf-8"))

    def request(self, command, arguments=None, timeout=15.0):
        """Sends a request and returns its response, or None if the adapter went away."""
        waiter = queue.Queue(1)
        with self._lock:
            self._seq += 1
            seq = self._seq
            self._pending[seq] = waiter
            message = {"seq": seq, "type": "request", "command": command}
            if arguments is not None:
                message["arguments"] = arguments
            payload = json.dumps(message).encode("utf-8")
            if self.log_json:
                print(f"[dap] -> {json.dumps(message)}", flush=True)
            self.sock.sendall(
                f"Content-Length: {len(payload)}\r\n\r\n".encode("utf-8") + payload
            )
        try:
            return waiter.get(timeout=timeout)
        except queue.Empty:
            return None

    def wait_event(self, name, timeout=20.0):
        """Waits for a named event, discarding others. Returns None on timeout or EOF."""
        deadline = timeout
        while deadline > 0:
            try:
                event = self.events.get(timeout=min(2.0, deadline))
            except queue.Empty:
                deadline -= 2.0
                continue
            if event is None:
                return None
            if event.get("event") == name:
                return event
        return None


def main():
    parser = argparse.ArgumentParser(description="Minimal DAP client for the BeanShell agent")
    parser.add_argument("port", type=int)
    parser.add_argument("--source", required=True, help="path of the script being debugged")
    parser.add_argument("--breakpoints", default="", help="comma-separated line numbers")
    parser.add_argument("--stops", type=int, default=1, help="how many stops to inspect")
    parser.add_argument("--evaluate", default="", help="expression to evaluate at each stop")
    parser.add_argument("--set", default="", help="name=value to assign at each stop")
    parser.add_argument("--json", action="store_true", help="dump every message")
    args = parser.parse_args()
    lines = [int(x) for x in args.breakpoints.split(",") if x.strip()]

    sock = socket.create_connection(("127.0.0.1", args.port), timeout=30)
    print(f"[dap] attached to 127.0.0.1:{args.port}", flush=True)
    conn = DapConnection(sock, args.json)

    response = conn.request("initialize", {"adapterID": "bsh", "linesStartAt1": True})
    if response is None or not response.get("success"):
        sys.exit("[dap] initialize failed")
    capabilities = response.get("body", {})
    print(f"[dap] initialize ok, capabilities: {sorted(capabilities.keys())}", flush=True)

    if conn.wait_event("initialized", timeout=10) is None:
        sys.exit("[dap] no initialized event")
    print("[dap] initialized event received", flush=True)

    response = conn.request(
        "setBreakpoints",
        {"source": {"path": args.source}, "breakpoints": [{"line": n} for n in lines]},
    )
    verified = [b.get("line") for b in (response or {}).get("body", {}).get("breakpoints", [])]
    print(f"[dap] setBreakpoints ok, verified lines: {verified}", flush=True)

    conn.request("configurationDone")
    print("[dap] configurationDone", flush=True)

    for index in range(args.stops):
        stopped = conn.wait_event("stopped", timeout=30)
        if stopped is None:
            print("[dap] no further stops", flush=True)
            break
        thread_id = stopped.get("body", {}).get("threadId")
        print(f"[dap] stopped: thread={thread_id} reason={stopped['body'].get('reason')}", flush=True)

        threads = conn.request("threads")
        names = [(t.get("id"), t.get("name")) for t in (threads or {}).get("body", {}).get("threads", [])]
        print(f"[dap] threads: {names}", flush=True)

        stack = conn.request("stackTrace", {"threadId": thread_id})
        frames = (stack or {}).get("body", {}).get("stackFrames", [])
        for frame in frames:
            source = frame.get("source", {}).get("name", "?")
            print(f"[dap]   frame {frame.get('id')}: {frame.get('name')} at {source}:{frame.get('line')}",
                  flush=True)

        if frames:
            frame_id = frames[0]["id"]
            scopes = conn.request("scopes", {"frameId": frame_id})
            for scope in (scopes or {}).get("body", {}).get("scopes", []):
                print(f"[dap]   scope {scope.get('name')} ref={scope.get('variablesReference')}",
                      flush=True)
                variables = conn.request(
                    "variables", {"variablesReference": scope.get("variablesReference")}
                )
                for variable in (variables or {}).get("body", {}).get("variables", []):
                    ref = variable.get("variablesReference", 0)
                    suffix = f" (+{ref})" if ref else ""
                    print(f"[dap]     {variable.get('name')} = {variable.get('value')}"
                          f" [{variable.get('type')}]{suffix}", flush=True)

            if args.evaluate:
                result = conn.request(
                    "evaluate", {"expression": args.evaluate, "frameId": frame_id, "context": "watch"}
                )
                if result and result.get("success"):
                    print(f"[dap]   evaluate {args.evaluate!r} -> {result['body'].get('result')}"
                          f" [{result['body'].get('type')}]", flush=True)
                else:
                    print(f"[dap]   evaluate {args.evaluate!r} FAILED:"
                          f" {(result or {}).get('message')}", flush=True)

            if args.set:
                name, _, value = args.set.partition("=")
                scopes_body = (scopes or {}).get("body", {}).get("scopes", [])
                if scopes_body:
                    result = conn.request(
                        "setVariable",
                        {
                            "variablesReference": scopes_body[0].get("variablesReference"),
                            "name": name,
                            "value": value,
                        },
                    )
                    if result and result.get("success"):
                        print(f"[dap]   setVariable {name} -> {result['body'].get('value')}", flush=True)
                    else:
                        print(f"[dap]   setVariable {name} FAILED: {(result or {}).get('message')}",
                              flush=True)

        conn.request("continue", {"threadId": thread_id})
        print(f"[dap] continued thread={thread_id}", flush=True)

    conn.request("disconnect", {"terminateDebuggee": False}, timeout=5)
    print("[dap] disconnected", flush=True)


if __name__ == "__main__":
    main()
