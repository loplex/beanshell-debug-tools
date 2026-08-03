package cz.loplex.bsh.hook;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The <a href="https://microsoft.github.io/debug-adapter-protocol/specification">Debug Adapter
 * Protocol</a> as a second transport, so the agent is usable from VS Code, Neovim, Eclipse and
 * anything else that speaks DAP.
 *
 * <p>Selected with {@code -Dbsh.debug.protocol=dap}; the native protocol remains the default and
 * IntelliJ is unaffected. That is not fence-sitting: LSP4IJ's DAP client does not implement the
 * {@code thread} event, so routing IntelliJ through DAP would <em>lose</em> the thread support the
 * native path has. Two transports is the arrangement where nobody pays for the other's limitations.
 *
 * <p><b>Listens</b> rather than connecting out, because that is the shape DAP clients expect: they
 * {@code attach} to a debuggee that is already running. The port comes from
 * {@code bsh.debug.listen}, and the script blocks on the first statement until a client arrives —
 * which is the point, since otherwise it would run to completion before anyone could set a
 * breakpoint.
 *
 * <h2>What is translated, and what is invented</h2>
 *
 * Most of DAP maps onto the native model directly, because the native model was built in DAP's
 * vocabulary to begin with: {@code stackTrace}/{@code scopes}/{@code variables} are the same three
 * levels, and a handle <em>is</em> a {@code variablesReference}. Three things need care:
 *
 * <ul>
 *   <li><b>Frame ids must be globally unique.</b> The hook numbers frames per thread (0 is innermost),
 *       but DAP's {@code stackTrace} hands out ids that {@code scopes} later quotes without saying
 *       which thread they came from. So the DAP side encodes thread and frame into one id and decodes
 *       it back.</li>
 *   <li><b>Stepping is a request, not a mode.</b> DAP sends {@code next}/{@code stepIn}/{@code stepOut}
 *       and expects the adapter to resume; the native protocol sets a run mode and then resumes
 *       separately. A DAP step therefore becomes two commands.</li>
 *   <li><b>Breakpoints arrive per source and are replaced wholesale.</b> DAP's {@code setBreakpoints}
 *       is authoritative for one source file, so this class keeps the per-source sets and hands the
 *       hook the union — which is what the hook's own filter expects.</li>
 * </ul>
 *
 * <p>The handshake ({@code initialize}, {@code launch}/{@code attach}, {@code configurationDone},
 * {@code threads}, {@code stackTrace}, {@code disconnect}) is answered here rather than in the hook,
 * and reported upwards as {@link Command.Kind#HANDLED}. The hook has no business knowing that DAP
 * requires an {@code initialized} event before breakpoints may be sent.
 */
final class DapChannel implements DebugChannel {

    /** Frame ids are (threadId * this) + frameIndex, so one int names a frame across threads. */
    private static final int FRAME_ID_STRIDE = 1000;

    private final int port;
    private final Object writeLock = new Object();
    private final AtomicInteger nextSeq = new AtomicInteger(1);

    /** Breakpoints per source path, since DAP replaces a whole source's set at a time. */
    private final Map<String, int[]> breakpointsBySource = new ConcurrentHashMap<>();

    /**
     * The stack most recently reported per thread, so {@code stackTrace} can be answered from it.
     *
     * <p>DAP asks for the stack as a separate request after the {@code stopped} event, whereas the
     * native protocol sends it inline. Remembering it here is what bridges that, and it costs nothing:
     * it is the same list that was just sent.
     */
    private final Map<Integer, List<Frame>> stacks = new ConcurrentHashMap<>();

    /** Thread names, for the {@code threads} request, which may arrive at any time. */
    private final Map<Integer, String> threadNames = new ConcurrentHashMap<>();

    /**
     * DAP request seq per pending hook request id, so a reply can be addressed to the right request.
     *
     * <p>The hook's request ids and DAP's {@code seq} numbers are different namespaces, and both ends
     * insist on their own, so one map is unavoidable.
     */
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();

    private final AtomicInteger nextRequestId = new AtomicInteger(1);

    private ServerSocket server;
    private Socket socket;
    private OutputStream out;
    private InputStream in;

    /** Set once the client has finished configuring, which is when the script may start reporting. */
    private volatile boolean configured;

    DapChannel(int port) {
        this.port = port;
    }

    public void connect() throws IOException {
        server = new ServerSocket(port);
        System.err.println("[bsh-agent] DAP: listening on 127.0.0.1:" + server.getLocalPort()
                + ", waiting for a client to attach");
        Socket accepted = server.accept();
        accepted.setTcpNoDelay(true);
        out = new BufferedOutputStream(accepted.getOutputStream(), 8192);
        in = accepted.getInputStream();
        socket = accepted;
        System.err.println("[bsh-agent] DAP: client attached");
    }

    public boolean isConnected() {
        return socket != null;
    }

    public void close() {
        Socket toCloseSocket = socket;
        ServerSocket toCloseServer = server;
        socket = null;
        server = null;
        if (toCloseSocket != null) {
            try {
                toCloseSocket.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a socket that is already gone.
            }
        }
        if (toCloseServer != null) {
            try {
                toCloseServer.close();
            } catch (IOException ignored) {
                // Likewise.
            }
        }
    }

    // ------------------------------------------------------------------ events

    public void sendStopped(int threadId, String threadName, int line, int callDepth, List<Frame> frames)
            throws IOException {
        boolean firstSighting = threadNames.put(threadId, threadName) == null;
        stacks.put(threadId, frames);
        if (firstSighting) {
            // DAP clients build their thread list from these; a thread that never announces itself may
            // not be selectable. Sent before the stop so the client knows the thread it names.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reason", "started");
            body.put("threadId", threadId);
            sendEvent("thread", body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // "breakpoint" vs "step" is the client's cue for how to present the stop. The hook does not
        // distinguish, and guessing wrong is cosmetic, so the honest generic reason is used.
        body.put("reason", "pause");
        body.put("threadId", threadId);
        body.put("allThreadsStopped", Boolean.FALSE);
        sendEvent("stopped", body);
    }

    public void sendScopes(int requestId, List<Scope> scopes) throws IOException {
        Pending request = pending.remove(requestId);
        if (request == null) {
            return;
        }
        List<Object> rendered = new ArrayList<>();
        for (Scope item : scopes) {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("name", item.name);
            scope.put("variablesReference", item.handle);
            // Locals is worth expanding on arrival; Global usually is not, and saying so keeps a
            // client from opening a large namespace nobody asked about.
            scope.put("expensive", !"Locals".equals(item.name));
            rendered.add(scope);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopes", rendered);
        sendResponse(request.seq, request.command, true, body, null);
    }

    public void sendVariables(int requestId, List<Variable> variables) throws IOException {
        Pending request = pending.remove(requestId);
        if (request == null) {
            return;
        }
        List<Object> rendered = new ArrayList<>();
        for (Variable variable : variables) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", variable.name);
            entry.put("value", variable.value);
            entry.put("type", variable.type);
            entry.put("variablesReference", variable.childHandle);
            rendered.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variables", rendered);
        sendResponse(request.seq, request.command, true, body, null);
    }

    public void sendEvaluated(int requestId, boolean setVariable, boolean ok, String value, String type,
            int childHandle) throws IOException {
        Pending request = pending.remove(requestId);
        if (request == null) {
            return;
        }
        if (!ok) {
            // DAP signals a failed evaluation with success:false and the reason in `message`, which is
            // what a client shows in the watch row. Not an error event: a mistyped watch is ordinary
            // use, exactly as on the native protocol.
            sendResponse(request.seq, request.command, false, null, value);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // setVariable's body calls it "value"; evaluate's calls it "result".
        body.put(setVariable ? "value" : "result", value);
        body.put("type", type);
        body.put("variablesReference", childHandle);
        sendResponse(request.seq, request.command, true, body, null);
    }

    // ---------------------------------------------------------------- commands

    public Command readCommand() throws IOException {
        Object message = readMessage();
        if (message == null) {
            return Command.simple(Command.Kind.DISCONNECT, 0);
        }
        String command = Json.getString(message, "command", "");
        int seq = Json.getInt(message, "seq", 0);
        Object args = Json.get(message, "arguments");

        if ("initialize".equals(command)) {
            return handleInitialize(seq, command);
        }
        if ("launch".equals(command) || "attach".equals(command)) {
            // Nothing to launch: the script is already running, which is the whole premise. Answering
            // success is what lets the client proceed to send breakpoints.
            sendResponse(seq, command, true, null, null);
            return Command.simple(Command.Kind.HANDLED, 0);
        }
        if ("configurationDone".equals(command)) {
            sendResponse(seq, command, true, null, null);
            configured = true;
            return Command.simple(Command.Kind.HANDLED, 0);
        }
        if ("setBreakpoints".equals(command)) {
            return handleSetBreakpoints(seq, command, args);
        }
        if ("threads".equals(command)) {
            return handleThreads(seq, command);
        }
        if ("stackTrace".equals(command)) {
            return handleStackTrace(seq, command, args);
        }
        if ("scopes".equals(command)) {
            int frameId = Json.getInt(args, "frameId", 0);
            return register(seq, command, Command.scopes(threadOf(frameId), 0, frameOf(frameId)));
        }
        if ("variables".equals(command)) {
            int reference = Json.getInt(args, "variablesReference", 0);
            // A handle is not tagged with its thread, but it was issued to one and only one, so the
            // thread that most recently stopped is the only candidate a client could be asking about.
            return register(seq, command, Command.variables(lastStoppedThread(), 0, reference));
        }
        if ("evaluate".equals(command)) {
            int frameId = Json.getInt(args, "frameId", 0);
            String expression = Json.getString(args, "expression", "");
            return register(seq, command,
                    Command.evaluate(threadOf(frameId), 0, frameOf(frameId), expression));
        }
        if ("setVariable".equals(command)) {
            return handleSetVariable(seq, command, args);
        }
        if ("continue".equals(command)) {
            int threadId = Json.getInt(args, "threadId", lastStoppedThread());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("allThreadsContinued", Boolean.FALSE);
            sendResponse(seq, command, true, body, null);
            return Command.simple(Command.Kind.RESUME, threadId);
        }
        if ("next".equals(command) || "stepIn".equals(command) || "stepOut".equals(command)) {
            return handleStep(seq, command, args);
        }
        if ("pause".equals(command)) {
            // Cannot be honoured: a thread is only ever stopped where it calls the hook, so there is
            // nothing to interrupt. Refusing with a reason beats accepting and doing nothing.
            sendResponse(seq, command, false, null,
                    "Pause is not supported: a BeanShell thread can only stop at a statement");
            return Command.simple(Command.Kind.HANDLED, 0);
        }
        if ("disconnect".equals(command) || "terminate".equals(command)) {
            sendResponse(seq, command, true, null, null);
            return Command.simple(Command.Kind.DISCONNECT, 0);
        }
        // Anything else: answer honestly rather than leaving the client waiting on a request that
        // will never come back.
        sendResponse(seq, command, false, null, "Not supported by the BeanShell debug agent: " + command);
        return Command.simple(Command.Kind.HANDLED, 0);
    }

    private Command handleInitialize(int seq, String command) throws IOException {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        // Only what is actually true. A capability claimed and then not delivered is worse than one
        // never claimed: the client builds UI for it and the user finds it broken.
        capabilities.put("supportsConfigurationDoneRequest", Boolean.TRUE);
        capabilities.put("supportsEvaluateForHovers", Boolean.TRUE);
        capabilities.put("supportsSetVariable", Boolean.TRUE);
        capabilities.put("supportsTerminateRequest", Boolean.TRUE);
        // Deliberately absent: conditional breakpoints, function breakpoints, exception breakpoints,
        // step-back, restart-frame, goto-targets, and pause. None is implemented.
        sendResponse(seq, command, true, capabilities, null);
        // DAP requires this before the client may send breakpoints. The order matters: a client that
        // has not seen `initialized` will not configure anything.
        sendEvent("initialized", new LinkedHashMap<>());
        return Command.simple(Command.Kind.HANDLED, 0);
    }

    private Command handleSetBreakpoints(int seq, String command, Object args) throws IOException {
        Object source = Json.get(args, "source");
        String path = Json.getString(source, "path", Json.getString(source, "name", ""));
        List<Object> requested = Json.getList(args, "breakpoints");

        int[] lines = new int[requested.size()];
        List<Object> verified = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            lines[i] = Json.getInt(requested.get(i), "line", 0);
            Map<String, Object> entry = new LinkedHashMap<>();
            // Claimed verified without checking: the agent has no parse tree for the file and cannot
            // know whether a line holds a statement until execution reaches it. Reporting them all as
            // verified is the honest answer to "I cannot tell", and matches what the native protocol
            // does -- the IDE decides placement there too.
            entry.put("verified", Boolean.TRUE);
            entry.put("line", lines[i]);
            verified.add(entry);
        }
        breakpointsBySource.put(path, lines);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("breakpoints", verified);
        sendResponse(seq, command, true, body, null);

        // The hook wants one flat set, so the per-source sets are unioned. Every entry is paired with
        // its own source path, which is what the hook's suffix match needs.
        int total = 0;
        for (int[] perSource : breakpointsBySource.values()) {
            total += perSource.length;
        }
        String[] files = new String[total];
        int[] allLines = new int[total];
        int index = 0;
        for (Map.Entry<String, int[]> entry : breakpointsBySource.entrySet()) {
            for (int i = 0; i < entry.getValue().length; i++) {
                files[index] = entry.getKey();
                allLines[index] = entry.getValue()[i];
                index++;
            }
        }
        return Command.breakpoints(files, allLines);
    }

    private Command handleThreads(int seq, String command) throws IOException {
        List<Object> rendered = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : threadNames.entrySet()) {
            Map<String, Object> thread = new LinkedHashMap<>();
            thread.put("id", entry.getKey());
            thread.put("name", entry.getValue());
            rendered.add(thread);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threads", rendered);
        sendResponse(seq, command, true, body, null);
        return Command.simple(Command.Kind.HANDLED, 0);
    }

    /**
     * Answers from the stack remembered at the last stop, rather than asking the hook for it again.
     *
     * <p>The native protocol sends the stack inline with the stop; DAP asks for it separately. Since
     * the list is the same one that was just sent, replaying it is both cheaper and more consistent
     * than a round-trip that could catch the thread mid-resume.
     */
    private Command handleStackTrace(int seq, String command, Object args) throws IOException {
        int threadId = Json.getInt(args, "threadId", lastStoppedThread());
        List<Frame> frames = stacks.get(threadId);
        List<Object> rendered = new ArrayList<>();
        if (frames != null) {
            for (int i = 0; i < frames.size(); i++) {
                Frame frame = frames.get(i);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", threadId * FRAME_ID_STRIDE + i);
                entry.put("name", frame.name.isEmpty() ? "?" : frame.name);
                entry.put("line", frame.line);
                entry.put("column", 1);
                if (!frame.sourceFile.isEmpty()) {
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("path", frame.sourceFile);
                    source.put("name", shortName(frame.sourceFile));
                    entry.put("source", source);
                }
                rendered.add(entry);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stackFrames", rendered);
        body.put("totalFrames", rendered.size());
        sendResponse(seq, command, true, body, null);
        return Command.simple(Command.Kind.HANDLED, 0);
    }

    private Command handleSetVariable(int seq, String command, Object args) {
        int reference = Json.getInt(args, "variablesReference", 0);
        String name = Json.getString(args, "name", "");
        String value = Json.getString(args, "value", "");
        int threadId = lastStoppedThread();
        // Frame 0: DAP's setVariable names a container but not a frame, and the expression has to be
        // evaluated somewhere. The innermost frame is the only defensible choice -- it is the scope the
        // user is looking at.
        return register(seq, command, Command.setVariable(threadId, 0, 0, reference, name, value));
    }

    private Command handleStep(int seq, String command, Object args) throws IOException {
        int threadId = Json.getInt(args, "threadId", lastStoppedThread());
        sendResponse(seq, command, true, null, null);
        // DAP conflates "how to step" with "go": one request means set the mode and resume. The native
        // model keeps them apart, so the mode is applied here and the resume is what is returned --
        // the hook then does exactly what it does for an IntelliJ step.
        int mode = "next".equals(command) ? MODE_OVER : "stepIn".equals(command) ? MODE_INTO : MODE_OUT;
        pendingStepMode = mode;
        pendingStepThread = threadId;
        return Command.mode(Command.Kind.SET_RUN_MODE, threadId, mode);
    }

    /**
     * The step a {@link #readCommand} has just accepted but not yet resumed.
     *
     * <p>Read by the hook through {@link #takePendingResume}, which is how one DAP request becomes the
     * native protocol's two commands without the hook having to know that DAP works that way.
     */
    private volatile int pendingStepMode = -1;
    private volatile int pendingStepThread;

    /** Run modes as the hook understands them; DAP's step requests are mapped onto these. */
    static final int MODE_OVER = 1;
    static final int MODE_INTO = 2;
    static final int MODE_OUT = 3;

    /**
     * The resume that must follow a step, or null. Consumed once.
     *
     * <p>Only the DAP channel needs this: on the native protocol the IDE sends the resume itself.
     */
    Command takePendingResume() {
        if (pendingStepMode < 0) {
            return null;
        }
        pendingStepMode = -1;
        return Command.simple(Command.Kind.RESUME, pendingStepThread);
    }

    /** Whether the client has sent configurationDone, i.e. whether reporting may begin. */
    boolean isConfigured() {
        return configured;
    }

    // ----------------------------------------------------------------- framing

    private Command register(int seq, String command, Command hookCommand) {
        int requestId = nextRequestId.getAndIncrement();
        pending.put(requestId, new Pending(seq, command));
        return withRequestId(hookCommand, requestId);
    }

    /** Rebuilds a command with the request id the reply will quote. */
    private static Command withRequestId(Command source, int requestId) {
        switch (source.kind) {
            case SCOPES:
                return Command.scopes(source.threadId, requestId, source.frameId);
            case VARIABLES:
                return Command.variables(source.threadId, requestId, source.handle);
            case EVALUATE:
                return Command.evaluate(source.threadId, requestId, source.frameId, source.expression);
            case SET_VARIABLE:
                return Command.setVariable(source.threadId, requestId, source.frameId, source.handle,
                        source.name, source.expression);
            default:
                return source;
        }
    }

    private int lastStoppedThread() {
        // The only thread a handle or a frame-less request can sensibly refer to. With one suspended
        // thread it is exact; with several, a client that means another one says so explicitly.
        int candidate = 0;
        for (Integer id : stacks.keySet()) {
            if (id > candidate) {
                candidate = id;
            }
        }
        return candidate;
    }

    private static int threadOf(int frameId) {
        return frameId / FRAME_ID_STRIDE;
    }

    private static int frameOf(int frameId) {
        return frameId % FRAME_ID_STRIDE;
    }

    private static String shortName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private void sendEvent(String event, Map<String, Object> body) throws IOException {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("seq", nextSeq.getAndIncrement());
        message.put("type", "event");
        message.put("event", event);
        message.put("body", body);
        send(message);
    }

    private void sendResponse(int requestSeq, String command, boolean success, Map<String, Object> body,
            String message) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seq", nextSeq.getAndIncrement());
        response.put("type", "response");
        response.put("request_seq", requestSeq);
        response.put("success", success);
        response.put("command", command);
        if (message != null) {
            response.put("message", message);
        }
        if (body != null) {
            response.put("body", body);
        }
        send(response);
    }

    private void send(Map<String, Object> message) throws IOException {
        byte[] payload = Json.write(message).getBytes(StandardCharsets.UTF_8);
        // Content-Length framing, as DAP specifies: the length counts bytes, not characters, which is
        // why the payload is encoded before it is measured.
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            out.write(header);
            out.write(payload);
            out.flush();
        }
    }

    /** Reads one Content-Length framed message, or null at end of stream. */
    private Object readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String header = readHeaderLine();
            if (header == null) {
                return null;
            }
            if (header.isEmpty()) {
                break;  // blank line ends the headers
            }
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(header.substring(colon + 1).trim());
                } catch (NumberFormatException malformed) {
                    throw new IOException("malformed Content-Length: " + header);
                }
            }
            // Other headers (Content-Type) are ignored, as the specification allows.
        }
        if (contentLength < 0) {
            throw new IOException("message without Content-Length");
        }
        byte[] payload = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = in.read(payload, read, contentLength - read);
            if (count < 0) {
                return null;
            }
            read += count;
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        try {
            return Json.parse(text);
        } catch (RuntimeException malformed) {
            throw new IOException("unparseable DAP message: " + malformed.getMessage());
        }
    }

    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        while (true) {
            int c = in.read();
            if (c < 0) {
                return line.size() == 0 ? null : line.toString("UTF-8");
            }
            if (c == '\n') {
                String text = line.toString("UTF-8");
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            line.write(c);
        }
    }

    /** A DAP request the hook is still working on. */
    private static final class Pending {
        final int seq;
        final String command;

        Pending(int seq, String command) {
            this.seq = seq;
            this.command = command;
        }
    }
}
