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
 * The instrumented script calls {@link #step(int, Object)} in front of every
 * statement. On the first call the agent connects to the IDE's debug server
 * (port passed via the {@code bsh.debug.port} system property), then for each
 * step it sends the current line and the caller's variables and blocks until the
 * IDE releases it. This keeps the script's own stdout/stderr completely clean.
 *
 * <p>Intentionally pure JDK + reflection (no Kotlin, no IntelliJ, no compile-time
 * BeanShell dependency) so it loads cleanly in the target JVM. If no debug server
 * is configured, or the connection drops, the agent disables itself and the
 * script keeps running normally.
 */
public final class BshDebugAgent {

    /** System property carrying the IDE debug server port. */
    public static final String PORT_PROPERTY = "bsh.debug.port";

    private static final int MAX_VALUE_LENGTH = 1000;
    private static final Object LOCK = new Object();

    private static boolean disabled;
    private static Socket socket;
    private static DataOutputStream out;
    private static DataInputStream in;

    private BshDebugAgent() {
    }

    /** Invoked by the instrumented script before each statement. */
    public static void step(int line, Object namespace) {
        synchronized (LOCK) {
            if (disabled) {
                return;
            }
            if (socket == null && !connect()) {
                disabled = true;
                return;
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

    private static boolean connect() {
        String port = System.getProperty(PORT_PROPERTY);
        if (port == null) {
            return false;
        }
        try {
            socket = new Socket("127.0.0.1", Integer.parseInt(port));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            in = new DataInputStream(socket.getInputStream());
            return true;
        } catch (Exception ex) {
            close();
            return false;
        }
    }

    private static Map<String, String> readVariables(Object namespace) {
        Map<String, String> variables = new LinkedHashMap<String, String>();
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
        if (value == null) {
            return "null";
        }
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
