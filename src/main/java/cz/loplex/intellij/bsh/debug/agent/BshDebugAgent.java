package cz.loplex.intellij.bsh.debug.agent;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
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
                Map<String, String> variables = readVariables(namespace);
                out.writeInt(line);
                out.writeInt(callDepth());
                out.writeInt(variables.size());
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
                out.flush();
                in.readByte(); // block until the IDE resumes this step
            } catch (IOException ex) {
                // The session dropped mid-run (e.g. the IDE stopped debugging). Detach
                // quietly and let the script finish rather than aborting the host process.
                System.err.println("[bsh-debug] debug session disconnected; continuing without debugging");
                disabled = true;
                close();
            }
        }
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
