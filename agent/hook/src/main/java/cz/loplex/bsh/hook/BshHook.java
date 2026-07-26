package cz.loplex.bsh.hook;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * <p>The outbound wire format is unchanged from the script-rewriting agent this replaces, so an
 * unmodified IDE keeps working: per reported statement it writes {@code line}, {@code depth},
 * {@code variableCount} and then {@code name}/{@code value} pairs, then blocks for a response.
 * The return channel gained optional commands — see {@link #CMD_RESUME} and friends — but the
 * single {@code 0x01} byte the old IDE sends still means "resume", and an IDE that sends nothing
 * else gets exactly the old behaviour. Failure handling matches too, see {@link #onEval}.
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
     * Upper bound on the children of one value. Lazy expansion removes the cost of unopened
     * objects, not the cost of an opened one, and a million-element list would still stall the
     * interpreter thread while it serialised.
     */
    private static final int MAX_CHILDREN = 1000;

    /** Named rather than imported: the hook is on the bootstrap classpath and cannot see bsh. */
    private static final String PRIMITIVE_CLASS = "bsh.Primitive";

    /**
     * Process exit code when a debug port is configured but the IDE server cannot be reached.
     * 69 is sysexits' {@code EX_UNAVAILABLE}.
     */
    private static final int EXIT_DEBUG_UNAVAILABLE = 69;

    /**
     * Nodes that are containers rather than step positions, so they are never reported in their
     * own right: {@code BSHBlock} would add a stop on the {@code {} of every braced body, and
     * {@code BSHSwitchLabel} is a {@code case} label, not a statement.
     */
    private static final Set<String> NEVER_REPORTED = new HashSet<String>(Arrays.asList(
            "BSHBlock",
            "BSHSwitchLabel"));

    /*
     * Which children of a control-flow node sit at statement position.
     *
     * A container's children mix statements with its header, so "child of a container" is not the
     * same as "statement". The layouts below were read off the parse trees rather than assumed:
     *
     *     BSHIfStatement            [cond, then, else?]        statements at index >= 1
     *     BSHWhileStatement while   [cond, body]               statement is last
     *     BSHWhileStatement do      [body, cond]               statement is FIRST
     *     BSHForStatement           [init?, cond?, update?, body]   statement is last
     *     BSHEnhancedForStatement   [type?, iterable, body]    statement is last
     *     BSHSwitchStatement        [expr, label, stmt, ...]   statements at index >= 1
     *
     * Two traps are worth naming. `do` and `while` are the same node type — the grammar declares
     * DoStatement() as #WhileStatement — but their children are in opposite order, so "last
     * child" is wrong for `do`; they are told apart by the package-private isDoStatement field.
     * And the optional parts of `for` and the optional type of an enhanced `for` change the child
     * count, which is why those rules are expressed relative to the end rather than as fixed
     * indices.
     *
     * This is per-node-type knowledge, which the transformer deliberately avoids — but here it
     * degrades gracefully. An unrecognised container simply reports none of its direct children,
     * exactly as before this rule existed, so a future BeanShell that renames or reshapes a node
     * loses brace-less-body coverage instead of misbehaving.
     */
    private static final String IF_STATEMENT = "BSHIfStatement";
    private static final String WHILE_STATEMENT = "BSHWhileStatement";
    private static final String FOR_STATEMENT = "BSHForStatement";
    private static final String ENHANCED_FOR_STATEMENT = "BSHEnhancedForStatement";
    private static final String SWITCH_STATEMENT = "BSHSwitchStatement";
    private static final String BLOCK = "BSHBlock";

    /*
     * Commands the IDE may send on the return channel.
     *
     * The original protocol had the IDE reply with a single byte to release a reported statement.
     * That byte is now RESUME, and any number of other commands may precede it, so an IDE that
     * only ever sends 0x01 keeps working unchanged.
     *
     * Until the IDE sends SET_BREAKPOINTS at least once every statement is reported, because an
     * older IDE that configures nothing must not go blind. Once it does, the agent falls silent
     * while running and speaks up only at a breakpoint, which removes the round-trip per
     * statement that made a plain loop crawl.
     */
    private static final int CMD_RESUME = 0x01;
    private static final int CMD_SET_BREAKPOINTS = 0x02;
    private static final int CMD_SET_RUN_MODE = 0x03;

    /**
     * Requests the IDE may issue while a statement is suspended, each answered with the matching
     * {@code EVT_*} reply before the loop goes back to waiting.
     *
     * <p>They are served on the interpreter thread, from inside the same command loop that waits
     * for {@link #CMD_RESUME}. That is not a shortcut: the thread is parked there anyway, it is
     * the thread that owns the BeanShell state being inspected, and answering anywhere else would
     * need a lock BeanShell does not offer.
     */
    private static final int CMD_SCOPES = 0x04;
    private static final int CMD_VARIABLES = 0x05;

    /*
     * The agent-to-IDE direction is opcode-tagged as of protocol 2. It used to be a bare stream of
     * statement reports, which left no room for a reply to travel back the other way.
     *
     * There is no negotiation and no fallback to the old shape, because there is nothing to
     * negotiate with: the agent jar ships inside the plugin, so both ends are always the same
     * build. The tools in plugin/tools speak this format too.
     */
    private static final int EVT_STOPPED = 0x10;
    private static final int EVT_SCOPES = 0x11;
    private static final int EVT_VARIABLES = 0x12;

    /**
     * Handle 0 is never issued, so the IDE can use it to mean "this value has no children" without
     * a separate flag on every variable.
     */
    private static final int NO_HANDLE = 0;

    /** Not stepping. Any other mode means the IDE wants to see every statement. */
    private static final int MODE_RUN = 0;

    /**
     * Breakpoint lines mapped to the source-file suffixes they apply to. Keyed by line because
     * that is the cheap discriminator — almost no statement shares a line with a breakpoint, so
     * the string comparison runs only on the rare hit. Null until the IDE configures it.
     */
    private static Map<Integer, List<String>> breakpointsByLine;

    /**
     * Anything other than {@link #MODE_RUN} means the user is stepping, so every statement is
     * reported and the IDE decides. Stepping is interactive and a round-trip there is invisible;
     * running is what needed fixing.
     */
    private static int runMode = MODE_RUN;

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
    private static Method nodeGetNumChildren;
    private static Method nodeGetChild;
    private static Field whileIsDoStatement;
    private static Method callStackDepth;
    private static Method callStackTop;
    private static Method nameSpaceGetVariableNames;
    private static Method nameSpaceGetVariable;
    private static Method nameSpaceGetParent;
    private static Method callStackToArray;
    private static Method nameSpaceGetName;
    private static Field nameSpaceCallerInfoNode;
    private static Class<?> nameSpaceClass;
    private static boolean reflectionFailed;

    /**
     * Objects the IDE may ask to expand, valid only for the current stop.
     *
     * <p>Discarded on every resume, which is the whole reason handles are safe: the IDE can never
     * hold a reference into a script that has moved on, so there is no stale-object problem to
     * solve and no cleanup protocol to get wrong. This mirrors DAP, where a
     * {@code variablesReference} is explicitly invalid once execution continues.
     */
    private static final Map<Integer, Object> handles = new HashMap<Integer, Object>();
    private static int nextHandle = 1;

    /** The frames of the current stop, innermost first. Empty while running. */
    private static Object[] currentFrames = new Object[0];

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
            if (!shouldReport(sourceFile, line)) {
                // Still let the IDE change its mind mid-run, e.g. when the user adds a
                // breakpoint while the script is running.
                drainPendingCommands();
                return;
            }
            report(line, sourceFile, callstack);
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
     * A node is reported when it sits at statement position: a bare parse-tree root (how
     * {@code Interpreter} evaluates a top-level statement), any child of a {@code BSHBlock}, or
     * the body child of a control-flow node.
     */
    private static boolean isStatement(Object node) throws Exception {
        if (NEVER_REPORTED.contains(simpleName(node))) {
            return false;
        }
        Object parent = nodeGetParent.invoke(node);
        if (parent == null) {
            return true;
        }
        String parentName = simpleName(parent);
        if (BLOCK.equals(parentName)) {
            // The hot path: every child of a block is a statement, so no index lookup is needed.
            return true;
        }

        int count = (Integer) nodeGetNumChildren.invoke(parent);
        int index = indexOfChild(parent, node, count);
        if (index < 0) {
            return false;
        }
        if (IF_STATEMENT.equals(parentName) || SWITCH_STATEMENT.equals(parentName)) {
            return index >= 1;
        }
        if (FOR_STATEMENT.equals(parentName) || ENHANCED_FOR_STATEMENT.equals(parentName)) {
            return index == count - 1;
        }
        if (WHILE_STATEMENT.equals(parentName)) {
            return isDoStatement(parent) ? index == 0 : index == count - 1;
        }
        return false;
    }

    /** Identity search: nodes have no usable equals(), and the same subtree never repeats. */
    private static int indexOfChild(Object parent, Object child, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            if (nodeGetChild.invoke(parent, Integer.valueOf(i)) == child) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code do ... while} and {@code while} share BSHWhileStatement but order their children
     * oppositely. The distinguishing field is package-private; if a BeanShell build does not have
     * it, assume the {@code while} layout, which is the common case.
     */
    private static boolean isDoStatement(Object whileNode) {
        try {
            if (whileIsDoStatement == null) {
                Field field = whileNode.getClass().getDeclaredField("isDoStatement");
                field.setAccessible(true);
                whileIsDoStatement = field;
            }
            return whileIsDoStatement.getBoolean(whileNode);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String simpleName(Object o) {
        if (o == null) {
            return "<root>";
        }
        String name = o.getClass().getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /**
     * Whether this statement is worth a round-trip to the IDE.
     *
     * <p>Reports while stepping, at a configured breakpoint, or as long as the IDE has never
     * configured any breakpoints — the last case is what keeps an IDE speaking only the original
     * one-byte protocol fully functional.
     */
    private static boolean shouldReport(String sourceFile, int line) {
        Map<Integer, List<String>> configured = breakpointsByLine;
        if (configured == null || runMode != MODE_RUN) {
            return true;
        }
        List<String> files = configured.get(Integer.valueOf(line));
        if (files == null) {
            return false;
        }
        if (sourceFile == null) {
            return false;
        }
        for (int i = 0; i < files.size(); i++) {
            if (sourceFile.endsWith(files.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Consumes any commands the IDE has already sent, without waiting for more.
     *
     * <p>Checking {@code available()} outside the lock is a benign race: losing it only means
     * entering the lock and finding nothing to read.
     */
    private static void drainPendingCommands() {
        try {
            if (socket == null || in.available() <= 0) {
                return;
            }
            synchronized (LOCK) {
                while (in.available() > 0) {
                    if (readCommand() == CMD_RESUME) {
                        // A stray resume with nothing suspended; nothing to release.
                        return;
                    }
                }
            }
        } catch (IOException ex) {
            System.err.println("[bsh-agent] debug session disconnected; continuing without debugging");
            disabled = true;
            close();
        }
    }

    /** Blocks until the IDE releases this statement, applying any commands sent first. */
    private static void readCommandsUntilResume() throws IOException {
        while (readCommand() != CMD_RESUME) {
            // keep applying commands
        }
    }

    /** Reads and applies one command, returning its opcode. Caller holds {@link #LOCK}. */
    private static int readCommand() throws IOException {
        int command = in.readByte() & 0xFF;
        switch (command) {
            case CMD_SET_BREAKPOINTS:
                readBreakpoints();
                break;
            case CMD_SET_RUN_MODE:
                runMode = in.readByte() & 0xFF;
                break;
            case CMD_SCOPES:
                writeScopes(in.readInt());
                break;
            case CMD_VARIABLES:
                writeVariables(in.readInt());
                break;
            default:
                // RESUME, and anything unrecognised, which a future IDE must not be able to
                // wedge the agent with. Treating it as a release is the safe reading: the worst
                // case is a script that keeps running.
                break;
        }
        return command;
    }

    private static void readBreakpoints() throws IOException {
        int count = in.readInt();
        Map<Integer, List<String>> parsed = new HashMap<Integer, List<String>>();
        for (int i = 0; i < count; i++) {
            String file = in.readUTF();
            int line = in.readInt();
            Integer key = Integer.valueOf(line);
            List<String> files = parsed.get(key);
            if (files == null) {
                files = new ArrayList<String>(2);
                parsed.put(key, files);
            }
            files.add(file);
        }
        // Published as a whole so a concurrent shouldReport() never sees a half-built map.
        breakpointsByLine = parsed;
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

    private static void report(int line, String sourceFile, Object callstack) throws Exception {
        int depth = (Integer) callStackDepth.invoke(callstack);
        Object[] frames = frames(callstack);
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
            currentFrames = frames;
            try {
                out.writeByte(EVT_STOPPED);
                out.writeInt(line);
                out.writeInt(depth);
                out.writeInt(frames.length);
                for (int i = 0; i < frames.length; i++) {
                    out.writeUTF(frameName(frames[i]));
                    // Frame 0 sits at the statement being reported; every outer frame sits at the
                    // call site recorded by the frame below it. Reading getInvocationLine() off the
                    // frame itself would be off by one level -- it answers "where was I called
                    // from", which is a position in the *next* frame out.
                    Object site = i == 0 ? null : callerInfoNode(frames[i - 1]);
                    out.writeUTF(i == 0 ? nullToEmpty(sourceFile) : nullToEmpty(nodeSourceFile(site)));
                    out.writeInt(i == 0 ? line : nodeLine(site));
                }
                out.flush();
                readCommandsUntilResume();
            } catch (IOException ex) {
                System.err.println("[bsh-agent] debug session disconnected; continuing without debugging");
                disabled = true;
                close();
            } finally {
                releaseHandles();
                currentFrames = new Object[0];
            }
        }
    }

    /** The call stack, innermost frame first. Empty rather than null if the shape is unexpected. */
    private static Object[] frames(Object callstack) {
        try {
            Object array = callStackToArray.invoke(callstack);
            if (array instanceof Object[]) {
                return (Object[]) array;
            }
        } catch (Throwable ignored) {
            // Fall through: a stack we cannot read is reported as no stack, not as a failure.
        }
        return new Object[0];
    }

    private static String frameName(Object namespace) {
        try {
            return nullToEmpty((String) nameSpaceGetName.invoke(namespace));
        } catch (Throwable t) {
            return "";
        }
    }

    private static Object callerInfoNode(Object namespace) {
        if (nameSpaceCallerInfoNode == null) {
            return null;
        }
        try {
            return nameSpaceCallerInfoNode.get(namespace);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String nodeSourceFile(Object node) {
        if (node == null) {
            return null;
        }
        try {
            return (String) nodeGetSourceFile.invoke(node);
        } catch (Throwable t) {
            return null;
        }
    }

    /** -1 where there is no script position, which is also what bsh uses for "entered from Java". */
    private static int nodeLine(Object node) {
        if (node == null) {
            return -1;
        }
        try {
            return (Integer) nodeGetLineNumber.invoke(node);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Answers {@link #CMD_SCOPES}: the scopes of one frame, each a handle the IDE can expand.
     *
     * <p>One scope per frame today. The shape is DAP's rather than the simplest possible one
     * because splitting locals from a closure's captured namespace is a presentation change here
     * and a protocol change if the level does not exist.
     */
    private static void writeScopes(int frameId) throws IOException {
        Object namespace = frameId >= 0 && frameId < currentFrames.length ? currentFrames[frameId] : null;
        out.writeByte(EVT_SCOPES);
        if (namespace == null) {
            out.writeInt(0);
        } else {
            out.writeInt(1);
            out.writeUTF("Locals");
            out.writeInt(handleFor(namespace));
        }
        out.flush();
    }

    /**
     * Answers {@link #CMD_VARIABLES}: the children of one handle, each with a handle of its own
     * when it can be expanded further.
     */
    private static void writeVariables(int handle) throws IOException {
        List<String[]> children;
        List<Object> values;
        Object target = handles.get(Integer.valueOf(handle));
        children = new ArrayList<String[]>();
        values = new ArrayList<Object>();
        try {
            if (isNameSpace(target)) {
                collectNamespace(target, children, values);
            } else if (target != null) {
                collectValue(target, children, values);
            }
        } catch (Throwable ignored) {
            // Send whatever was gathered; an unreadable object is not worth failing the session.
        }
        out.writeByte(EVT_VARIABLES);
        out.writeInt(children.size());
        for (int i = 0; i < children.size(); i++) {
            String[] entry = children.get(i);
            out.writeUTF(entry[0]);
            out.writeUTF(entry[1]);
            out.writeUTF(entry[2]);
            out.writeInt(expandable(values.get(i)) ? handleFor(values.get(i)) : NO_HANDLE);
        }
        out.flush();
    }

    /**
     * Variables visible in a frame, walking the namespace scope chain outwards, inner winning.
     *
     * <p>This is where the agent differs from rewriting the script: a BeanShell closure is a
     * {@code NameSpace} kept alive by a {@code This} reference, so a variable can live several
     * parents above the current frame. Reporting only the innermost namespace — all a script-level
     * hook can reach — shows the wrong set of variables inside any closure.
     */
    private static void collectNamespace(Object namespace, List<String[]> children, List<Object> values)
            throws Exception {
        Set<String> seen = new HashSet<String>();
        while (namespace != null) {
            Object names = nameSpaceGetVariableNames.invoke(namespace);
            if (names instanceof String[]) {
                for (String name : (String[]) names) {
                    if (name == null || name.equals("bsh") || !seen.add(name)) {
                        continue;
                    }
                    Object value;
                    try {
                        value = nameSpaceGetVariable.invoke(namespace, name);
                    } catch (Throwable t) {
                        value = "<unavailable>";
                    }
                    add(children, values, name, value);
                }
            }
            namespace = nameSpaceGetParent.invoke(namespace);
        }
    }

    /** Children of an ordinary value: array elements, collection entries, map entries, or fields. */
    private static void collectValue(Object target, List<String[]> children, List<Object> values) {
        if (target.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(target);
            for (int i = 0; i < length; i++) {
                add(children, values, "[" + i + "]", java.lang.reflect.Array.get(target, i));
            }
            return;
        }
        if (target instanceof Map) {
            int i = 0;
            for (Object o : ((Map<?, ?>) target).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
                add(children, values, String.valueOf(entry.getKey()), entry.getValue());
                if (++i >= MAX_CHILDREN) {
                    break;
                }
            }
            return;
        }
        if (target instanceof Iterable) {
            int i = 0;
            for (Object element : (Iterable<?>) target) {
                add(children, values, "[" + i + "]", element);
                if (++i >= MAX_CHILDREN) {
                    break;
                }
            }
            return;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            Field[] fields = type.getDeclaredFields();
            for (Field field : fields) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(target);
                } catch (Throwable t) {
                    value = "<unavailable>";
                }
                add(children, values, field.getName(), value);
                if (children.size() >= MAX_CHILDREN) {
                    return;
                }
            }
        }
    }

    private static void add(List<String[]> children, List<Object> values, String name, Object value) {
        children.add(new String[] { name, truncate(render(value)), typeName(value) });
        values.add(value);
    }

    /**
     * Whether a value is worth a handle. Leaves are not given one, so the IDE shows no expander
     * where there is nothing behind it.
     */
    private static boolean expandable(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character || value instanceof Enum) {
            return false;
        }
        // bsh.Primitive wraps every scripted int, boolean and so on, including NULL and VOID. Its
        // fields are the wrapper's, not the user's, so offering an expander on `x = 42` would show
        // BeanShell's plumbing where the value already says everything.
        if (PRIMITIVE_CLASS.equals(value.getClass().getName())) {
            return false;
        }
        if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value) > 0;
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        return true;
    }

    /**
     * A value's own rendering, still a {@code toString()} but only ever fetched for values the IDE
     * actually looked at, rather than for every variable on every step.
     */
    private static String render(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable t) {
            return "<toString() threw " + t.getClass().getName() + ">";
        }
    }

    private static String typeName(Object value) {
        return value == null ? "" : simpleName(value);
    }

    private static boolean isNameSpace(Object candidate) {
        return candidate != null && nameSpaceClass != null && nameSpaceClass.isInstance(candidate);
    }

    /** Handles are identity-based, so expanding the same object twice does not grow the table. */
    private static int handleFor(Object value) {
        for (Map.Entry<Integer, Object> entry : handles.entrySet()) {
            if (entry.getValue() == value) {
                return entry.getKey().intValue();
            }
        }
        int handle = nextHandle++;
        handles.put(Integer.valueOf(handle), value);
        return handle;
    }

    private static void releaseHandles() {
        handles.clear();
        // Handles keep counting up rather than restarting at 1, so a reply that crosses a resume
        // cannot be mistaken for an answer about a different object.
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
                nodeGetNumChildren = accessible(node.getClass().getMethod("jjtGetNumChildren"));
                nodeGetChild = accessible(node.getClass().getMethod("jjtGetChild", int.class));

                Class<?> callStackClass = callstack.getClass();
                callStackDepth = accessible(callStackClass.getMethod("depth"));
                callStackTop = accessible(callStackClass.getMethod("top"));
                callStackToArray = accessible(callStackClass.getMethod("toArray"));

                nameSpaceClass = callStackTop.invoke(callstack).getClass();
                nameSpaceGetVariableNames = accessible(nameSpaceClass.getMethod("getVariableNames"));
                nameSpaceGetVariable = accessible(nameSpaceClass.getMethod("getVariable", String.class));
                nameSpaceGetParent = accessible(nameSpaceClass.getMethod("getParent"));
                nameSpaceGetName = accessible(nameSpaceClass.getMethod("getName"));
                // Package-private: the field holding the node a frame was invoked from. Optional --
                // without it the stack still reports, just with every frame at the current line.
                try {
                    nameSpaceCallerInfoNode = nameSpaceClass.getDeclaredField("callerInfoNode");
                    nameSpaceCallerInfoNode.setAccessible(true);
                } catch (Throwable t) {
                    nameSpaceCallerInfoNode = null;
                }
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
