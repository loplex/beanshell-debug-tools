package cz.loplex.bsh.hook;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Called from every instrumented BeanShell AST evaluation.
 *
 * <p>Loaded by the <b>bootstrap classloader</b> so that instrumented {@code bsh.*} classes can
 * resolve it whichever loader they came from — a requirement for the Maven case, where BeanShell
 * lives in a plugin classloader. The price is that this class cannot be linked against any
 * BeanShell type (the bootstrap loader cannot see them), hence {@code Object} parameters and
 * reflection throughout. Configuration arrives through system properties for the same reason.
 *
 * <p>Wire format is unchanged from the script-rewriting agent it replaces, so the IDE side needs
 * no modification: per reported statement it writes {@code line}, {@code depth},
 * {@code variableCount} and then {@code name}/{@code value} pairs, and blocks on a single
 * response byte. Failure handling matches too — see {@link #onEval}.
 */
public final class BshHook {

    /** System property carrying the IDE debug server port. */
    public static final String PORT_PROPERTY = "bsh.debug.port";

    /**
     * Comma-separated list of source files to report on; a node is reported when its
     * {@code getSourceFile()} ends with one of the entries. Unset means report everything.
     *
     * <p>Needed because instrumenting the interpreter reaches strictly more code than rewriting
     * a script does: BeanShell commands such as {@code print} and {@code pwd} are themselves
     * {@code .bsh} files loaded from the classpath, so without a filter the IDE stops inside the
     * jar on every {@code print()} call.
     */
    public static final String SOURCES_PROPERTY = "bsh.debug.sources";

    /**
     * When set, report to stderr instead of the socket. A development aid for checking which
     * nodes qualify as statements without needing an IDE or a listener on the other end.
     */
    public static final String TRACE_PROPERTY = "bsh.debug.trace";

    /**
     * Upper bound on a reported variable value, in chars. {@link DataOutputStream#writeUTF(String)}
     * caps the encoded string at 65535 bytes of modified UTF-8 (up to 3 bytes per char), so this
     * stays safely below: 20000 * 3 = 60000 &lt; 65535.
     */
    private static final int MAX_VALUE_LENGTH = 20000;

    /**
     * Process exit code when a debug port is configured but the IDE server cannot be reached.
     * 69 is sysexits' {@code EX_UNAVAILABLE}.
     */
    private static final int EXIT_DEBUG_UNAVAILABLE = 69;

    /**
     * AST nodes all of whose children sit at statement position.
     *
     * <p>Matched by simple name rather than by type, to stay version-independent.
     *
     * <p>Only {@code BSHBlock} qualifies, and the restriction is deliberate. Control-flow nodes
     * do contain statements, but they mix them with their header: the children of a
     * {@code BSHForStatement} are the init list, the condition, the update list <i>and</i> the
     * body, all reporting the line of the {@code for} keyword. Treating every child of a
     * container as a statement therefore fires once per header part on every iteration. Telling
     * body from header needs per-node child-index knowledge, which is exactly the
     * version-specific coupling this agent avoids.
     *
     * <p>The cost is that a brace-less body ({@code if (x) foo();}) and the statements of a
     * {@code switch} are not stoppable. That matches the script-rewriting instrumenter, whose
     * pure-insertion test rejects those same positions, so this is parity rather than a
     * regression — see the TODO in {@link #isStatement}.
     */
    private static final Set<String> STATEMENT_CONTAINERS = new HashSet<String>(Arrays.asList(
            "BSHBlock"));

    /**
     * Guards against re-entering the interpreter. Reading variables — and, later, evaluating
     * watch expressions — runs BeanShell code, which is itself instrumented. Without this a
     * single reported statement would recurse until the stack overflowed.
     */
    private static final ThreadLocal<Boolean> REPORTING = new ThreadLocal<Boolean>();

    private static final Object LOCK = new Object();

    private static final int port;
    private static final String[] sources;
    private static final boolean trace;
    private static boolean disabled;
    private static Socket socket;
    private static DataOutputStream out;
    private static DataInputStream in;

    // Reflection handles, resolved once. Every BSH* node inherits these from the
    // package-private bsh.SimpleNode, so a single Method works for all of them.
    private static Method nodeGetLineNumber;
    private static Method nodeGetParent;
    private static Method nodeGetSourceFile;
    private static Method callStackDepth;
    private static Method callStackTop;
    private static Method nameSpaceGetVariableNames;
    private static Method nameSpaceGetVariable;
    private static Method nameSpaceGetParent;
    private static boolean reflectionFailed;

    static {
        int parsed = -1;
        String portProperty = System.getProperty(PORT_PROPERTY);
        if (portProperty != null) {
            try {
                parsed = Integer.parseInt(portProperty);
            } catch (NumberFormatException ex) {
                // Throwing here would turn every later onEval() call into a cryptic
                // NoClassDefFoundError, so a malformed port simply means "no debugging".
                System.err.println("[bsh-agent] ignoring malformed " + PORT_PROPERTY + "='" + portProperty + "'");
            }
        }
        port = parsed;
        trace = System.getProperty(TRACE_PROPERTY) != null;

        String sourcesProperty = System.getProperty(SOURCES_PROPERTY);
        if (sourcesProperty == null || sourcesProperty.trim().isEmpty()) {
            sources = null;
        } else {
            String[] split = sourcesProperty.split(",");
            for (int i = 0; i < split.length; i++) {
                split[i] = split[i].trim();
            }
            sources = split;
        }

        // Tracing to stderr needs no listener, so it is a valid mode on its own.
        disabled = port == -1 && !trace;
    }

    private BshHook() {
    }

    /**
     * Invoked at the top of every instrumented {@code eval(CallStack, Interpreter)}.
     *
     * <p>Most calls return immediately: only nodes sitting at statement position are reported.
     * Failures are handled exactly as in the previous agent — no port means the script is not
     * being debugged and runs untouched; a configured port with nothing listening aborts the
     * process, because silently skipping every breakpoint is the failure that looks like "it
     * just ran"; a session dropping mid-run only warns and detaches, since aborting what may be
     * a real Maven build would be worse.
     *
     * @param node        the AST node being evaluated ({@code bsh.SimpleNode})
     * @param callstack   {@code bsh.CallStack} for the current thread
     * @param interpreter {@code bsh.Interpreter} owning the evaluation
     */
    public static void onEval(Object node, Object callstack, Object interpreter) {
        if (disabled || Boolean.TRUE.equals(REPORTING.get())) {
            return;
        }
        REPORTING.set(Boolean.TRUE);
        try {
            if (!resolveReflection(node, callstack)) {
                return;
            }
            if (!isStatement(node)) {
                return;
            }
            int line = (Integer) nodeGetLineNumber.invoke(node);
            if (line < 0) {
                // Entered from Java code: bsh substitutes SimpleNode.JAVACODE, which reports
                // line -1. There is no script position to stop at.
                return;
            }
            String sourceFile = (String) nodeGetSourceFile.invoke(node);
            if (!isReportedSource(sourceFile)) {
                return;
            }
            if (trace) {
                System.err.println("[bsh-agent] " + simpleName(node)
                        + " parent=" + simpleName(nodeGetParent.invoke(node))
                        + " line=" + line
                        + " src=" + shortSource(sourceFile));
                return;
            }
            report(line, callstack);
        } catch (Throwable t) {
            // Never let a debugging problem change the behaviour of the debugged script.
            disabled = true;
            System.err.println("[bsh-agent] disabling instrumentation after an internal error: " + t);
            close();
        } finally {
            REPORTING.remove();
        }
    }

    /**
     * A node is reported when its parent is a statement container, or when it has no parent at
     * all (a top-level statement, which {@code Interpreter} evaluates as a bare parse-tree root).
     *
     * <p>{@code BSHBlock} itself is excluded: it is a container, not a step position, and
     * reporting it would add a stop on the {@code {} of every braced body that the
     * script-rewriting instrumenter never produced.
     *
     * <p>TODO brace-less bodies and {@code switch} statements. Both need a way to tell a
     * container's body children from its header children without hard-coding child indices per
     * node type. One option worth measuring: consult the script-rewriting instrumenter's
     * pure-insertion oracle once per file and cache the resulting set of statement lines.
     */
    private static boolean isStatement(Object node) throws Exception {
        if ("BSHBlock".equals(simpleName(node))) {
            return false;
        }
        Object parent = nodeGetParent.invoke(node);
        return parent == null || STATEMENT_CONTAINERS.contains(simpleName(parent));
    }

    private static String simpleName(Object o) {
        if (o == null) {
            return "<root>";
        }
        String name = o.getClass().getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** Applies the {@link #SOURCES_PROPERTY} filter; no filter configured means report all. */
    private static boolean isReportedSource(String sourceFile) {
        if (sources == null) {
            return true;
        }
        if (sourceFile == null) {
            return false;
        }
        for (String candidate : sources) {
            if (!candidate.isEmpty() && sourceFile.endsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String shortSource(String sourceFile) {
        if (sourceFile == null) {
            return "<unknown>";
        }
        int slash = sourceFile.lastIndexOf('/');
        return slash < 0 ? sourceFile : sourceFile.substring(slash + 1);
    }

    private static void report(int line, Object callstack) throws Exception {
        Map<String, String> variables = readVariables(callstack);
        int depth = (Integer) callStackDepth.invoke(callstack);
        synchronized (LOCK) {
            if (disabled) {
                return;
            }
            if (socket == null) {
                try {
                    connect();
                } catch (IOException ex) {
                    System.err.println("[bsh-agent] cannot reach the IDE debug server on 127.0.0.1:"
                            + port + " (" + ex + "); aborting");
                    System.exit(EXIT_DEBUG_UNAVAILABLE);
                }
            }
            try {
                out.writeInt(line);
                out.writeInt(depth);
                out.writeInt(variables.size());
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
                out.flush();
                in.readByte(); // block until the IDE resumes this step
            } catch (IOException ex) {
                System.err.println("[bsh-agent] debug session disconnected; continuing without debugging");
                disabled = true;
                close();
            }
        }
    }

    /**
     * Collects variables visible at the current statement by walking the namespace scope chain
     * outwards, inner scopes winning.
     *
     * <p>This is where the agent already differs from rewriting the script: a BeanShell closure
     * is a {@code NameSpace} kept alive by a {@code This} reference, so a variable can live
     * several parents above the current frame. Reporting only the innermost namespace — all a
     * script-level hook can reach — shows the wrong set of variables inside any closure.
     */
    private static Map<String, String> readVariables(Object callstack) {
        Map<String, String> variables = new LinkedHashMap<String, String>();
        try {
            Object namespace = callStackTop.invoke(callstack);
            while (namespace != null) {
                Object names = nameSpaceGetVariableNames.invoke(namespace);
                if (names instanceof String[]) {
                    for (String name : (String[]) names) {
                        if (name == null || name.equals("bsh") || variables.containsKey(name)) {
                            continue;
                        }
                        Object value;
                        try {
                            value = nameSpaceGetVariable.invoke(namespace, name);
                        } catch (Throwable t) {
                            value = "<unavailable>";
                        }
                        variables.put(name, truncate(String.valueOf(value)));
                    }
                }
                namespace = nameSpaceGetParent.invoke(namespace);
            }
        } catch (Throwable ignored) {
            // Report whatever was gathered before the failure.
        }
        return variables;
    }

    /**
     * Resolves the reflection handles on first use. Deferred rather than done in the static
     * initializer because the hook is loaded before any BeanShell type is available.
     */
    private static boolean resolveReflection(Object node, Object callstack) {
        if (nodeGetLineNumber != null) {
            return true;
        }
        if (reflectionFailed) {
            return false;
        }
        synchronized (LOCK) {
            if (nodeGetLineNumber != null) {
                return true;
            }
            try {
                // bsh.SimpleNode and bsh.Node are package-private, so the methods are only
                // reachable reflectively even though they are declared public.
                nodeGetLineNumber = accessible(node.getClass().getMethod("getLineNumber"));
                nodeGetParent = accessible(node.getClass().getMethod("jjtGetParent"));
                nodeGetSourceFile = accessible(node.getClass().getMethod("getSourceFile"));

                Class<?> callStackClass = callstack.getClass();
                callStackDepth = accessible(callStackClass.getMethod("depth"));
                callStackTop = accessible(callStackClass.getMethod("top"));

                Class<?> nameSpaceClass = callStackTop.invoke(callstack).getClass();
                nameSpaceGetVariableNames = accessible(nameSpaceClass.getMethod("getVariableNames"));
                nameSpaceGetVariable = accessible(nameSpaceClass.getMethod("getVariable", String.class));
                nameSpaceGetParent = accessible(nameSpaceClass.getMethod("getParent"));
                return true;
            } catch (Throwable t) {
                reflectionFailed = true;
                disabled = true;
                System.err.println("[bsh-agent] unexpected BeanShell API shape, not instrumenting: " + t);
                return false;
            }
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static void connect() throws IOException {
        socket = new Socket("127.0.0.1", port);
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        in = new DataInputStream(socket.getInputStream());
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
            // nothing useful to do
        }
        socket = null;
        out = null;
        in = null;
    }
}
