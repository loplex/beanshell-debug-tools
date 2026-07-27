package cz.loplex.intellij.bsh.debug.agent;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug agent injected into the forked BeanShell JVM.
 *
 * <p>The instrumented script calls {@link #step(int, Object)} in front of every
 * statement. On the first call the agent connects to the IDE's debug server
 * (port passed via the {@code bsh.debug.port} system property), then for each
 * step it sends the current line and the caller's variables and blocks until the
 * IDE releases it. This keeps the script's own stdout/stderr completely clean.
 *
 * <p>Intentionally pure JDK + reflection (no Kotlin, no IntelliJ, no compile-time
 * BeanShell dependency) so it loads cleanly in the target JVM.
 *
 * <p>Failure handling deliberately distinguishes three cases:
 * <ul>
 *   <li><b>No port configured</b> — the script is not being debugged; the agent
 *       disables itself and the script runs normally.</li>
 *   <li><b>A port is configured but the initial connection fails</b> — a real
 *       misconfiguration (you asked to debug, yet nothing is listening). Connecting
 *       is a precondition of debugging, not a step within it, so the agent aborts
 *       the process with a non-zero exit code instead of silently skipping every
 *       breakpoint (which is exactly the failure that looks like "it just ran").</li>
 *   <li><b>The session drops mid-run</b> — e.g. the developer stops debugging in
 *       the IDE. Aborting the host process (which may be a real Maven build) would
 *       be surprising, so the agent warns on stderr, detaches, and lets the script
 *       finish uninstrumented.</li>
 * </ul>
 */
public final class BshDebugAgent {

    /** System property carrying the IDE debug server port. */
    public static final String PORT_PROPERTY = "bsh.debug.port";

    /**
     * Upper bound on a reported variable value, in chars. {@link DataOutputStream#writeUTF(String)}
     * caps the encoded string at 65535 bytes of modified UTF-8 (up to 3 bytes per char), so
     * this stays safely below that: 20000 * 3 = 60000 &lt; 65535. Without the cap a single
     * large value (e.g. a big collection's {@code toString()}) would overflow {@code writeUTF}
     * and — now that mid-stream failures are visible — could abort the run.
     */
    private static final int MAX_VALUE_LENGTH = 20000;

    /**
     * Process exit code when a debug port is configured but the IDE server cannot be reached.
     * 69 is sysexits' {@code EX_UNAVAILABLE} ("a required service is unavailable") — a fitting,
     * distinctive non-zero code for "you asked to debug, but the debug server is not there".
     */
    private static final int EXIT_DEBUG_UNAVAILABLE = 69;

    private static final Object LOCK = new Object();

    private static final int port;
    private static boolean disabled;
    private static Socket socket;
    private static DataOutputStream out;
    private static DataInputStream in;

    static {
        int parsed = -1;
        String portProperty = System.getProperty(PORT_PROPERTY);
        if (portProperty != null) {
            try {
                parsed = Integer.parseInt(portProperty);
            } catch (NumberFormatException ex) {
                // Parsing in the static initializer must not throw: an
                // ExceptionInInitializerError would turn every later step() call into a
                // cryptic NoClassDefFoundError. Treat a malformed port as "no debugging".
                System.err.println("[bsh-debug] ignoring malformed " + PORT_PROPERTY + "='" + portProperty + "'");
            }
        }
        port = parsed;
        disabled = port == -1;
    }

    private BshDebugAgent() {
    }

    /** Invoked by the instrumented script before each statement. */
    public static void step(int line, Object namespace) {
        synchronized (LOCK) {
            if (disabled) {
                return;
            }
            if (socket == null) {
                try {
                    connect();
                } catch (IOException ex) {
                    // A port was configured but nothing is listening: the developer asked to
                    // debug and debugging cannot even start. Abort with a clear message and a
                    // non-zero exit rather than running the script as if nothing were wrong.
                    System.err.println("[bsh-debug] cannot reach the IDE debug server on 127.0.0.1:"
                            + port + " (" + ex + "); aborting");
                    System.exit(EXIT_DEBUG_UNAVAILABLE);
                }
            }
            try {
                // One frame, always. A rewritten script hands the hook a NameSpace, and a
                // NameSpace does not know its caller -- so unlike the instrumenting agent, which
                // is given the whole CallStack, this path cannot produce a stack at all. The frame
                // count in the protocol is what lets both report honestly.
                out.writeByte(EVT_STOPPED);
                out.writeInt(line);
                out.writeInt(callDepth());
                out.writeInt(1);
                out.writeUTF("script");
                out.writeUTF("");
                out.writeInt(line);
                out.flush();
                serveUntilResume(namespace);
            } catch (IOException ex) {
                // The session dropped mid-run (e.g. the IDE stopped debugging). Detach
                // quietly and let the script finish rather than aborting the host process.
                System.err.println("[bsh-debug] debug session disconnected; continuing without debugging");
                disabled = true;
                close();
            }
        }
    }

    /*
     * Protocol 2, the same wire format the instrumenting agent speaks -- see BshDebugProcess.
     * This path only ever needs a subset: it reports one frame and serves variable requests for
     * it, and it never receives breakpoints, so it keeps reporting every statement.
     */
    private static final int CMD_SCOPES = 0x04;
    private static final int CMD_VARIABLES = 0x05;
    private static final int CMD_EVALUATE = 0x06;
    private static final int CMD_SET_VARIABLE = 0x07;
    private static final int EVT_STOPPED = 0x10;
    private static final int EVT_SCOPES = 0x11;
    private static final int EVT_VARIABLES = 0x12;
    private static final int EVT_EVALUATED = 0x13;
    private static final int EVT_VARIABLE_SET = 0x14;
    private static final int NO_HANDLE = 0;

    /**
     * Why this path answers no to both evaluating requests.
     *
     * <p>Running an expression needs a {@code bsh.Interpreter}, and a rewritten script hands the hook
     * a {@code bsh.NameSpace} — which cannot evaluate anything. The IDE is told as much up front and
     * offers neither Watches nor Set Value here, so this reply is the belt to that braces: an
     * unrecognised opcode is treated as a resume, and silently continuing a script because the IDE
     * asked a question this path cannot answer would be much worse than an error message.
     */
    private static final String NOT_SUPPORTED =
            "Evaluation needs the instrumenting agent; this session rewrites the script instead";

    /** The one handle this path issues: the frame's namespace. Variables are flat, so no more. */
    private static final int NAMESPACE_HANDLE = 1;

    /** Blocks until the IDE resumes, answering whatever it asks about the current frame first. */
    private static void serveUntilResume(Object namespace) throws IOException {
        while (true) {
            int command = in.readByte() & 0xFF;
            if (command == CMD_SCOPES) {
                in.readInt(); // frame id; there is only ever frame 0 here
                out.writeByte(EVT_SCOPES);
                out.writeInt(1);
                out.writeUTF("Locals");
                out.writeInt(NAMESPACE_HANDLE);
                out.flush();
            } else if (command == CMD_VARIABLES) {
                int handle = in.readInt();
                Map<String, String> variables =
                        handle == NAMESPACE_HANDLE ? readVariables(namespace) : Collections.<String, String>emptyMap();
                out.writeByte(EVT_VARIABLES);
                out.writeInt(variables.size());
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                    out.writeUTF("");
                    // No nested expansion on this path: it reads values as strings out of the
                    // namespace and never holds the objects, so there is nothing to hand back.
                    out.writeInt(NO_HANDLE);
                }
                out.flush();
            } else if (command == CMD_EVALUATE) {
                in.readInt();  // frame id
                in.readUTF();  // expression
                refuse(EVT_EVALUATED);
            } else if (command == CMD_SET_VARIABLE) {
                in.readInt();  // frame id
                in.readInt();  // container handle
                in.readUTF();  // name
                in.readUTF();  // expression
                refuse(EVT_VARIABLE_SET);
            } else {
                // CMD_RESUME, and anything unrecognised, which must not be able to wedge a script.
                return;
            }
        }
    }

    /** Answers a request this path cannot serve, keeping the script suspended. */
    private static void refuse(int event) throws IOException {
        out.writeByte(event);
        out.writeBoolean(false);
        out.writeUTF(NOT_SUPPORTED);
        out.writeUTF("");
        out.writeInt(NO_HANDLE);
        out.flush();
    }

    /** Number of active BeanShell user-method frames — grows by a fixed amount per nested call. */
    private static int callDepth() {
        int depth = 0;
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if ("bsh.BshMethod".equals(element.getClassName())) {
                depth++;
            }
        }
        return depth;
    }

    private static void connect() throws IOException {
        socket = new Socket("127.0.0.1", port);
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        in = new DataInputStream(socket.getInputStream());
    }

    private static Map<String, String> readVariables(Object namespace) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (namespace == null) {
            return variables;
        }
        try {
            Method getNames = namespace.getClass().getMethod("getVariableNames");
            Method getVariable = namespace.getClass().getMethod("getVariable", String.class);
            Object names = getNames.invoke(namespace);
            if (names instanceof String[]) {
                for (String name : (String[]) names) {
                    if (name == null || name.equals("bsh")) {
                        continue;
                    }
                    Object value;
                    try {
                        value = getVariable.invoke(namespace, name);
                    } catch (Throwable t) {
                        value = "<unavailable>";
                    }
                    variables.put(name, truncate(String.valueOf(value)));
                }
            }
        } catch (Throwable ignored) {
            // Not a BeanShell namespace, or reflection failed; report no variables.
        }
        return variables;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH) + "…";
    }

    private static void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        socket = null;
        out = null;
        in = null;
    }
}
