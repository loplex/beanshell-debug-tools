package cz.loplex.bsh.hook;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Called from every instrumented BeanShell AST evaluation.
 *
 * <p>Loaded by the <b>bootstrap classloader</b> so that instrumented {@code bsh.*} classes can
 * resolve it whichever loader they came from — a requirement for the Maven case, where BeanShell
 * lives in a plugin classloader. The price is that this class cannot be linked against any
 * BeanShell type (the bootstrap loader cannot see them), hence {@code Object} parameters and
 * reflection throughout. Configuration arrives through system properties for the same reason.
 *
 * <p>The wire format is protocol 3, specified in {@code docs/PROTOCOL.md}. Per reported statement
 * the hook writes {@link #EVT_STOPPED} with the call stack and then blocks <b>that thread</b>,
 * answering whatever the IDE asks about its suspended frames — scopes, variables, an expression to
 * evaluate, a value to change — until it is resumed. Other script threads keep running and may
 * report alongside; see {@link ThreadState} and {@link #readerLoop} for how that works, and
 * {@link #report} for why only the reporting thread suspends. Failure handling is described on
 * {@link #onEval}.
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
     * Path to a file listing source-file <em>prefixes</em> to report on, one per line; a node is
     * reported when its {@code getSourceFile()} starts with one of them. Combines with
     * {@link #SOURCES_PROPERTY} as an OR — either match reports.
     *
     * <p>Exists because a script BeanShell was handed as a <em>string</em> has no file name. It gets
     * a synthetic one instead: {@code Interpreter.eval(String)} names the source
     * <code>inline evaluation of: ``&lt;the script, newlines flattened, cut at 80 chars&gt;''</code>.
     * That is the shape an inline Maven {@code <script>} arrives in, and matching it needs a prefix
     * rather than a suffix — the tail may be the {@code " . . . "} elision, and the script's own text
     * is in the middle. A prefix short enough to sit inside the 80 chars is also immune to that cut,
     * to the {@code ;} BeanShell appends, and to any trimming the calling plugin did.
     *
     * <p>A file rather than a property value because these strings contain the script's punctuation,
     * commas included, so no in-property separator is safe. A line, by contrast, cannot occur inside
     * one: BeanShell already replaced every newline with a space when it built the name.
     */
    public static final String SOURCE_PREFIXES_FILE_PROPERTY = "bsh.debug.sources.file";

    /**
     * Which wire protocol to speak: {@code native} (the default) or {@code dap}.
     *
     * <p>{@code native} is the compact binary protocol the IntelliJ plugin speaks, specified in
     * {@code docs/PROTOCOL.md}. {@code dap} is the Debug Adapter Protocol, for VS Code, Neovim,
     * Eclipse and anything else that speaks it.
     *
     * <p>Two transports rather than one, because neither subsumes the other in practice: LSP4IJ's DAP
     * client does not implement the {@code thread} event, so routing IntelliJ through DAP would lose
     * the thread support the native path has. Keeping both means neither side pays for the other's
     * limitations.
     */
    public static final String PROTOCOL_PROPERTY = "bsh.debug.protocol";

    /**
     * Port to <b>listen</b> on, for DAP. Defaults to {@link #PORT_PROPERTY} when unset.
     *
     * <p>The direction differs by protocol and it is not arbitrary. The native channel connects out,
     * because the IDE launches the process and already has a port. A DAP client instead expects to
     * <em>attach</em> to something running, so under DAP the agent listens and the script waits on its
     * first statement until a client arrives — without which it would finish before anyone could set a
     * breakpoint.
     */
    public static final String LISTEN_PORT_PROPERTY = "bsh.debug.listen";

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
     * Commands the IDE may send on the return channel. RESUME releases a reported statement; any
     * number of the others may precede it.
     *
     * Until the IDE sends SET_BREAKPOINTS at least once every statement is reported, because an IDE
     * that configures nothing must not go blind. Once it does, the agent falls silent while running
     * and speaks up only at a breakpoint, which removes the round-trip per statement that made a
     * plain loop crawl.
     */
    private static final int CMD_RESUME = 0x01;
    private static final int CMD_SET_BREAKPOINTS = 0x02;
    private static final int CMD_SET_RUN_MODE = 0x03;

    /**
     * Turns "report everything, on every thread" on and off, so the IDE can round up the other
     * threads when a breakpoint says Suspend: All. Global rather than per thread — that is what it
     * means. See {@link #catchAll}.
     */
    private static final int CMD_SET_CATCH_ALL = 0x08;

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
    private static final int CMD_EVALUATE = 0x06;
    private static final int CMD_SET_VARIABLE = 0x07;

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
    private static final int EVT_EVALUATED = 0x13;
    private static final int EVT_VARIABLE_SET = 0x14;

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
     * Everything about one script thread that used to be a static field.
     *
     * <p>Per thread because two script threads can be suspended at once, and each needs its own
     * frames, its own handle table, its own run mode and its own queue of commands to apply. Sharing
     * any of them would mean one thread's Step Over changing where the other stops, or a handle
     * issued to one being expanded against the other's namespace.
     *
     * <p>The {@link #mailbox} is what replaces "the suspended thread reads its own commands off the
     * socket" — see {@link #readerLoop}. Only ever touched by the reader thread (offer) and by its
     * own thread (take), so it needs no further locking.
     */
    private static final class ThreadState {

        /** Protocol id, ours rather than {@code Thread.getId()} — see {@link #stateFor}. */
        final int id;
        final String name;

        /** Commands the reader thread has handed to this thread, in arrival order. */
        final BlockingQueue<DebugChannel.Command> mailbox =
                new LinkedBlockingQueue<DebugChannel.Command>();

        /** Objects the IDE may expand, valid only for this thread's current stop. */
        final Map<Integer, Object> handles = new HashMap<Integer, Object>();

        /** The frames of this thread's current stop, innermost first. Empty while running. */
        Object[] frames = new Object[0];

        /**
         * The {@code bsh.Interpreter} of this thread's current stop, or null while running.
         *
         * <p>Held only for the duration of a stop: it is what makes evaluating an expression
         * possible, and holding it longer would pin an interpreter the script has finished with.
         */
        Object interpreter;

        /**
         * Anything other than {@link #MODE_RUN} means the user is stepping <em>this</em> thread, so
         * every statement on it is reported and the IDE decides. Stepping is interactive and a
         * round-trip there is invisible; running is what needed filtering.
         */
        int runMode = MODE_RUN;

        ThreadState(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** Live thread states by protocol id, for the reader thread to dispatch into. */
    private static final Map<Integer, ThreadState> threadsById =
            new ConcurrentHashMap<Integer, ThreadState>();

    /**
     * This thread's state, created on first use.
     *
     * <p>A {@code ThreadLocal} rather than a lookup by {@code Thread.currentThread()} because
     * {@link #onEval} consults it on every instrumented node — the hottest path in the agent.
     */
    private static final ThreadLocal<ThreadState> STATE = new ThreadLocal<ThreadState>();

    private static final AtomicInteger nextThreadId = new AtomicInteger(1);

    /**
     * Handle ids are allocated globally even though the tables are per thread.
     *
     * <p>Costs nothing and removes a whole class of confusion: a handle can only ever mean one
     * object, so a request that named the wrong thread fails to find it rather than silently
     * expanding a different thread's value that happened to share a number.
     */
    private static final AtomicInteger nextHandle = new AtomicInteger(1);

    /**
     * Guards against re-entering the interpreter. Reading a variable runs BeanShell code, and
     * evaluating a watch expression runs whatever the user typed — both of which are themselves
     * instrumented. Without this a single reported statement would recurse until the stack
     * overflowed. It stays set for the whole of {@link #report}, so everything served while
     * suspended is covered, including an expression that calls a script method.
     */
    private static final ThreadLocal<Boolean> REPORTING = new ThreadLocal<Boolean>();

    /**
     * Serialises writes to the socket, and nothing else.
     *
     * <p>This is the whole of what used to be {@code LOCK}. Before threads, one lock covered
     * connecting, writing, and being suspended — which is precisely why two threads could not be
     * suspended at once: the first held it for the duration of its stop. Now a stop holds no lock at
     * all; it parks on its own mailbox, and this guards only the moments when bytes are being put on
     * the wire, so a second thread can report while the first is still suspended.
     *
     * <p>A message must be written under a single acquisition, or two threads' fields would
     * interleave into an unparseable stream.
     */
    /**
     * Whether every thread should report its next statement, whatever the breakpoints say.
     *
     * <p>How Suspend: All is honoured without pretending to be JDWP. A thread cannot be frozen from
     * outside — it only ever stops where it calls the hook — so "suspend all" is implemented as
     * "everyone reports at the next statement, and the IDE decides who stays stopped". The IDE sets
     * this when a Suspend: All breakpoint is hit and clears it on resume.
     *
     * <p>Two honest consequences. It is <b>not instantaneous</b>: a thread sleeping, blocked, or deep
     * in Java code reports nothing until it next reaches a script statement, so it keeps running for
     * that long. And while set, every statement on every running thread costs a round-trip — which is
     * acceptable precisely because it only lasts while somebody is looking at a stopped thread.
     */
    private static volatile boolean catchAll;

    /**
     * How long the first statement waits for a DAP client to finish configuring.
     *
     * <p>Bounded so a client that attaches and then goes quiet cannot hang the host program. Timing out
     * runs the script unfiltered, which is the same outcome as an IDE that never sends breakpoints.
     */
    private static final long CONFIGURATION_TIMEOUT_MS = 30_000L;

    private static final Object WRITE_LOCK = new Object();

    /** Guards {@link #connect} and the reader-thread start, which must happen exactly once. */
    private static final Object CONNECT_LOCK = new Object();

    private static final int port;
    private static final String[] sources;
    private static final String[] sourcePrefixes;
    private static final boolean trace;
    private static boolean disabled;
    /**
     * The wire. One of {@link NativeChannel} or {@link DapChannel}, chosen once at class init.
     *
     * <p>Everything above this field is transport-independent: the hook decides what to say, the
     * channel decides how to encode it. That is what makes a second protocol a matter of one more
     * implementation rather than a second debugger.
     */
    private static DebugChannel channel;

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
    private static Method thisGetNameSpace;
    private static Method interpreterGetGlobalNameSpace;
    private static Method callStackToArray;
    private static Method nameSpaceGetName;
    private static Field nameSpaceCallerInfoNode;
    private static Class<?> nameSpaceClass;
    private static Class<?> thisClass;
    private static Method interpreterEval;
    private static Method primitiveGetType;
    private static boolean reflectionFailed;

    /**
     * Objects the IDE may ask to expand, valid only for the current stop.
     *
     * <p>Discarded on every resume, which is the whole reason handles are safe: the IDE can never
     * hold a reference into a script that has moved on, so there is no stale-object problem to
     * solve and no cleanup protocol to get wrong. This mirrors DAP, where a
     * {@code variablesReference} is explicitly invalid once execution continues.
     */
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

        sourcePrefixes = readSourcePrefixes(System.getProperty(SOURCE_PREFIXES_FILE_PROPERTY));

        boolean dap = "dap".equalsIgnoreCase(String.valueOf(System.getProperty(PROTOCOL_PROPERTY)));
        int listenPort = parsedPort(LISTEN_PORT_PROPERTY, port);
        if (dap && listenPort != -1) {
            channel = new DapChannel(listenPort);
        } else if (!dap && port != -1) {
            channel = new NativeChannel(port);
        } else {
            channel = null;
        }

        // Tracing to stderr needs no listener, so it is a valid mode on its own.
        disabled = channel == null && !trace;
    }

    private BshHook() {
    }

    /** A port property, or [fallback] when unset or unparseable. */
    private static int parsedPort(String property, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException malformed) {
            System.err.println("[bsh-agent] ignoring malformed " + property + "='" + value + "'");
            return fallback;
        }
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
            ThreadState state = stateFor();
            if (!shouldReport(state, sourceFile, line)) {
                // Still let the IDE change its mind mid-run, e.g. when the user adds a
                // breakpoint while the script is running.
                drainMailbox(state);
                return;
            }
            report(state, line, sourceFile, callstack, interpreter);
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
    private static boolean shouldReport(ThreadState state, String sourceFile, int line) {
        // Set while some thread is suspended under a Suspend: All breakpoint. Every other thread then
        // reports its next statement so the IDE gets the chance to hold it too. Deliberately checked
        // before the breakpoint map: the point is to report at a line that has no breakpoint.
        if (catchAll) {
            return true;
        }
        Map<Integer, List<String>> configured = breakpointsByLine;
        if (configured == null || state.runMode != MODE_RUN) {
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
            if (pathsMatch(sourceFile, files.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a reported source and a configured breakpoint path name the same file.
     *
     * <p>Matched as a suffix in <b>either</b> direction, because which of the two is the longer depends
     * on the client. The IntelliJ plugin sends absolute paths and BeanShell reports whatever the script
     * was launched with — so a script run as {@code bsh.Interpreter t.bsh} reports {@code t.bsh} while
     * the breakpoint says {@code /home/…/t.bsh}. A DAP client always sends absolute paths, which made
     * this the difference between breakpoints working and silently never firing.
     *
     * <p>Neither direction alone is enough and both together are still a heuristic: two files with the
     * same name in different directories are indistinguishable. That is inherent to matching on a name
     * the interpreter may have received relatively, not something a stricter rule here could fix.
     */
    private static boolean pathsMatch(String reported, String configured) {
        if (configured.isEmpty()) {
            return false;
        }
        return reported.endsWith(configured) || configured.endsWith(reported);
    }

    /**
     * Applies whatever the reader thread has already put in this thread's mailbox, without waiting.
     *
     * <p>Called on a statement that is <em>not</em> being reported, so the IDE can still change its
     * mind mid-run — adding a breakpoint, or starting to step — without the script having to stop
     * first.
     */
    private static void drainMailbox(ThreadState state) {
        DebugChannel.Command message;
        while ((message = state.mailbox.poll()) != null) {
            try {
                applyCommand(state, message);
            } catch (IOException ex) {
                sessionLost(ex);
                return;
            }
        }
    }

    /**
     * The one thread that reads the socket, dispatching each command to the thread it names.
     *
     * <p>The reason this exists is structural rather than tidiness. Before threads, a suspended
     * thread read its own commands off the socket — which cannot work once two are suspended, since
     * only one of them can be the reader. So reading is now somebody else's job, and a suspended
     * thread parks on {@link ThreadState#mailbox} instead.
     *
     * <p>It is a <b>daemon</b> thread, and that is not incidental: it lives in whatever JVM hosts
     * BeanShell — a Maven build, in the case this whole agent exists for — and a non-daemon thread
     * blocked on a socket read would keep that JVM alive after the build finished. Wrong lifecycle
     * here hangs somebody's build rather than a test.
     *
     * <p>Requests are still <em>served</em> on the thread that owns the state, from
     * {@link #applyCommand}. That was never a shortcut: only that thread can safely touch its own
     * BeanShell state, and answering from here would need a lock BeanShell does not offer.
     */
    private static void readerLoop() {
        try {
            while (true) {
                DebugChannel.Command command = channel.readCommand();
                if (command == null || command.kind == DebugChannel.Command.Kind.DISCONNECT) {
                    sessionLost(new IOException("client disconnected"));
                    return;
                }
                switch (command.kind) {
                    case HANDLED:
                        // The transport answered it itself (a DAP handshake message).
                        continue;
                    case SET_BREAKPOINTS:
                        // The one piece of shared configuration, so it is applied here rather than
                        // routed to a thread -- there may be no suspended thread to route it to,
                        // which is exactly when a client sends it.
                        applyBreakpoints(command);
                        continue;
                    case SET_CATCH_ALL:
                        catchAll = command.mode != 0;
                        continue;
                    default:
                        break;
                }
                ThreadState target = threadsById.get(Integer.valueOf(command.threadId));
                if (target == null) {
                    // A command for a thread that has exited. Dropping it is right: there is nobody
                    // to answer for it, and the client will have been told the thread is gone.
                    continue;
                }
                target.mailbox.offer(command);
                // DAP folds "how to step" and "go" into one request, so a step arrives as a mode with
                // an implied resume. Asking the channel for it here keeps that quirk out of the hook.
                if (channel instanceof DapChannel) {
                    DebugChannel.Command follow = ((DapChannel) channel).takePendingResume();
                    if (follow != null) {
                        target.mailbox.offer(follow);
                    }
                }
            }
        } catch (IOException ex) {
            sessionLost(ex);
        } catch (Throwable t) {
            System.err.println("[bsh-agent] reader thread failed; continuing without debugging: " + t);
            disabled = true;
            close();
        }
    }

    /**
     * Applies one command on the thread that owns the state, and answers it if it expects an answer.
     *
     * <p>Returns true when the thread should stop waiting. An unrecognised command counts as a
     * release: the worst case is a script that keeps running, whereas ignoring it could leave a thread
     * parked for good.
     */
    private static boolean applyCommand(ThreadState state, DebugChannel.Command command)
            throws IOException {
        switch (command.kind) {
            case SET_RUN_MODE:
                state.runMode = command.mode;
                return false;
            case SCOPES:
                channel.sendScopes(command.requestId, collectScopes(state, command.frameId));
                return false;
            case VARIABLES:
                channel.sendVariables(command.requestId, collectVariables(state, command.handle));
                return false;
            case EVALUATE: {
                Outcome outcome = evaluate(state, command.frameId, command.expression);
                sendOutcome(command.requestId, false, outcome);
                return false;
            }
            case SET_VARIABLE: {
                Outcome outcome = assign(state, command.frameId, command.handle, command.name,
                        command.expression);
                sendOutcome(command.requestId, true, outcome);
                return false;
            }
            default:
                return true;
        }
    }

    /** Writes an {@link Outcome} as the reply to whichever request produced it. */
    private static void sendOutcome(int requestId, boolean setVariable, Outcome outcome)
            throws IOException {
        channel.sendEvaluated(
                requestId,
                setVariable,
                outcome.ok,
                truncate(outcome.text),
                outcome.ok ? typeName(outcome.value) : "",
                outcome.ok && expandable(outcome.value) ? handleFor(outcome.state, outcome.value)
                        : NO_HANDLE);
    }

    /** Blocks on this thread's mailbox until the IDE releases it, applying anything sent first. */
    private static void awaitResume(ThreadState state) throws IOException {
        while (true) {
            DebugChannel.Command message;
            try {
                message = state.mailbox.take();
            } catch (InterruptedException ex) {
                // Somebody wants this thread to stop. Restore the flag and let the script continue --
                // staying parked would be worse than a missed breakpoint.
                Thread.currentThread().interrupt();
                return;
            }
            if (applyCommand(state, message)) {
                return;
            }
            if (disabled) {
                return;
            }
        }
    }

    /** One place for "the IDE went away", which must never abort the host program. */
    private static void sessionLost(IOException ex) {
        if (!disabled) {
            System.err.println("[bsh-agent] debug session disconnected; continuing without debugging ("
                    + ex + ")");
        }
        disabled = true;
        close();
        // Release everyone parked on a mailbox, or a suspended thread would wait for an IDE that is
        // gone. An empty message array is read as "unrecognised", which applyCommand treats as a
        // release.
        for (ThreadState waiting : threadsById.values()) {
            waiting.mailbox.offer(DebugChannel.Command.simple(
                    DebugChannel.Command.Kind.RESUME, waiting.id));
        }
    }

    /** Replaces the breakpoint set with the one the client just sent. */
    private static void applyBreakpoints(DebugChannel.Command command) {
        Map<Integer, List<String>> parsed = new HashMap<Integer, List<String>>();
        for (int i = 0; i < command.breakpointLines.length; i++) {
            Integer key = Integer.valueOf(command.breakpointLines[i]);
            List<String> files = parsed.get(key);
            if (files == null) {
                files = new ArrayList<String>(2);
                parsed.put(key, files);
            }
            files.add(command.breakpointFiles[i]);
        }
        // Published as a whole so a concurrent shouldReport() never sees a half-built map.
        breakpointsByLine = parsed;
    }

    /**
     * Applies the {@link #SOURCES_PROPERTY} (suffix) and {@link #SOURCE_PREFIXES_FILE_PROPERTY}
     * (prefix) filters; neither configured means report all.
     */
    private static boolean isReportedSource(String sourceFile) {
        if (sources == null && sourcePrefixes == null) {
            return true;
        }
        if (sourceFile == null) {
            return false;
        }
        if (sources != null) {
            for (String candidate : sources) {
                if (!candidate.isEmpty() && sourceFile.endsWith(candidate)) {
                    return true;
                }
            }
        }
        if (sourcePrefixes != null) {
            for (String candidate : sourcePrefixes) {
                if (!candidate.isEmpty() && sourceFile.startsWith(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reads the prefix list, or returns null when there is none to read.
     *
     * <p>An unreadable file returns null — "report everything" — rather than failing. A filter is an
     * optimisation over reporting every statement and letting the IDE decide; losing it costs speed,
     * whereas throwing here would abort somebody's build over a missing temp file.
     */
    private static String[] readSourcePrefixes(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return lines.isEmpty() ? null : lines.toArray(new String[lines.size()]);
        } catch (IOException ex) {
            System.err.println("[bsh-agent] cannot read " + SOURCE_PREFIXES_FILE_PROPERTY + "='" + path
                    + "', reporting every source: " + ex);
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Nothing useful to do about a failed close on a file we only read.
                }
            }
        }
    }

    private static String shortSource(String sourceFile) {
        if (sourceFile == null) {
            return "<unknown>";
        }
        int slash = sourceFile.lastIndexOf('/');
        return slash < 0 ? sourceFile : sourceFile.substring(slash + 1);
    }

    /**
     * Reports one statement and blocks this thread until the IDE releases it.
     *
     * <p><b>Only this thread is suspended.</b> Other script threads keep running, and if one of them
     * reaches a breakpoint it reports and suspends alongside. That is not a simplification of
     * JDWP-style "suspend all" — it is what an instrumenting agent can actually do. A thread can only
     * be stopped where it calls the hook, so there is no way to freeze a thread that is in the middle
     * of Java code; "suspend all" would really mean "stop the others whenever they next reach a
     * statement", which is a different thing wearing the same name. Honest scope beats a familiar
     * label here.
     */
    private static void report(ThreadState state, int line, String sourceFile, Object callstack,
            Object interpreter) throws Exception {
        int depth = (Integer) callStackDepth.invoke(callstack);
        Object[] frames = frames(callstack);
        if (disabled) {
            return;
        }
        if (!ensureConnected()) {
            return;
        }
        state.frames = frames;
        state.interpreter = interpreter;
        try {
            List<DebugChannel.Frame> reported = new ArrayList<DebugChannel.Frame>(frames.length);
            for (int i = 0; i < frames.length; i++) {
                // Frame 0 sits at the statement being reported; every outer frame sits at the call
                // site recorded by the frame below it. Reading getInvocationLine() off the frame
                // itself would be off by one level -- it answers "where was I called from", which is
                // a position in the *next* frame out.
                Object site = i == 0 ? null : callerInfoNode(frames[i - 1]);
                reported.add(new DebugChannel.Frame(
                        frameName(frames[i]),
                        i == 0 ? nullToEmpty(sourceFile) : nullToEmpty(nodeSourceFile(site)),
                        i == 0 ? line : nodeLine(site)));
            }
            channel.sendStopped(state.id, state.name, line, depth, reported);
            awaitResume(state);
        } catch (IOException ex) {
            sessionLost(ex);
        } finally {
            releaseHandles(state);
            state.frames = new Object[0];
            state.interpreter = null;
        }
    }

    /**
     * Connects on first use and starts the reader thread, exactly once.
     *
     * <p>Returns false when debugging has been given up on, so the caller reports nothing. A
     * configured port with nothing listening still aborts the process: silently skipping every
     * breakpoint is the failure that looks like "it just ran".
     */
    private static boolean ensureConnected() {
        if (channel != null && channel.isConnected()) {
            return waitForConfiguration();
        }
        synchronized (CONNECT_LOCK) {
            if (disabled || channel == null) {
                return false;
            }
            if (channel.isConnected()) {
                return waitForConfiguration();
            }
            try {
                channel.connect();
            } catch (IOException ex) {
                System.err.println("[bsh-agent] cannot establish the debug connection on port "
                        + port + " (" + ex + "); aborting");
                System.exit(EXIT_DEBUG_UNAVAILABLE);
            }
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    readerLoop();
                }
            }, "bsh-agent-reader");
            reader.setDaemon(true);
            reader.start();
        }
        return waitForConfiguration();
    }

    /**
     * Under DAP, blocks until the client has finished configuring.
     *
     * <p>DAP has a handshake the native protocol does not: a client sends {@code initialize}, waits for
     * {@code initialized}, sends its breakpoints, then {@code configurationDone}. Reporting before that
     * last step means reporting before the breakpoints exist — for a short script, the whole run could
     * be over first. So the first statement waits.
     *
     * <p>Bounded, because a client that attaches and then never configures must not hang the host
     * program. Timing out means the script runs on unfiltered, which is the same outcome as an IDE that
     * never sends a breakpoint set.
     */
    private static boolean waitForConfiguration() {
        if (!(channel instanceof DapChannel)) {
            return true;
        }
        DapChannel dap = (DapChannel) channel;
        long deadline = System.currentTimeMillis() + CONFIGURATION_TIMEOUT_MS;
        while (!dap.isConfigured() && !disabled) {
            if (System.currentTimeMillis() > deadline) {
                System.err.println("[bsh-agent] DAP: client did not finish configuring within "
                        + CONFIGURATION_TIMEOUT_MS + "ms; running on");
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return !disabled;
    }

    /**
     * This thread's state, registered on first use so the reader can dispatch to it.
     *
     * <p>The protocol id is ours rather than {@code Thread.getId()}: ids start at 1 and stay small,
     * which keeps them readable in a trace, and a JVM's thread ids are neither.
     */
    private static ThreadState stateFor() {
        ThreadState existing = STATE.get();
        if (existing != null) {
            return existing;
        }
        Thread current = Thread.currentThread();
        ThreadState created = new ThreadState(nextThreadId.getAndIncrement(), current.getName());
        STATE.set(created);
        threadsById.put(Integer.valueOf(created.id), created);
        return created;
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
     * <p>Two scopes, and the second is the point of having the level at all: <b>Global</b> is the
     * interpreter's own namespace, which is where a script's top-level state lives once execution has
     * descended into a method. Without it, stopping inside a method shows the method's locals and
     * nothing else — the script's own globals become invisible exactly when they are most likely to
     * be what is wrong.
     *
     * <p>Global is omitted when it <i>is</i> the frame's namespace (a stop at top level, where the two
     * are the same object) and when there is no interpreter to ask — the rewriting path, which is
     * handed a namespace only.
     */
    private static List<DebugChannel.Scope> collectScopes(ThreadState state, int frameId) {
        Object namespace = frame(state, frameId);
        Object global = globalNameSpace(state);
        boolean hasGlobal = global != null && global != namespace;
        List<DebugChannel.Scope> scopes = new ArrayList<DebugChannel.Scope>(2);
        if (namespace != null) {
            scopes.add(new DebugChannel.Scope("Locals", handleFor(state, namespace)));
        }
        if (hasGlobal) {
            scopes.add(new DebugChannel.Scope("Global", handleFor(state, global)));
        }
        return scopes;
    }

    /**
     * The children of one handle, each with a handle of its own when it can be expanded further.
     */
    private static List<DebugChannel.Variable> collectVariables(ThreadState state, int handle) {
        Object target = state.handles.get(Integer.valueOf(handle));
        List<String[]> children = new ArrayList<String[]>();
        List<Object> values = new ArrayList<Object>();
        try {
            if (isNameSpace(target)) {
                collectNamespace(target, children, values);
            } else if (isThis(target)) {
                // Expand a This as the scope it stands for, not as a Java object. Its Java fields are
                // the interpreter's plumbing; the namespace is the script's own view of that scope.
                // Falls through to reflection if the namespace cannot be read, rather than showing
                // nothing.
                Object namespace = thisNameSpace(target);
                if (namespace != null) {
                    collectNamespace(namespace, children, values);
                } else {
                    collectValue(target, children, values);
                }
            } else if (target != null) {
                collectValue(target, children, values);
            }
        } catch (Throwable ignored) {
            // Send whatever was gathered; an unreadable object is not worth failing the session.
        }
        List<DebugChannel.Variable> variables = new ArrayList<DebugChannel.Variable>(children.size());
        for (int i = 0; i < children.size(); i++) {
            String[] entry = children.get(i);
            variables.add(new DebugChannel.Variable(entry[0], entry[1], entry[2],
                    expandable(values.get(i)) ? handleFor(state, values.get(i)) : NO_HANDLE));
        }
        return variables;
    }

    /** The namespace of one frame of this thread's current stop, or null when there is no such frame. */
    private static Object frame(ThreadState state, int frameId) {
        return frameId >= 0 && frameId < state.frames.length ? state.frames[frameId] : null;
    }

    /** Either a value, or the reason there is not one. Both are ordinary answers. */
    private static final class Outcome {
        final boolean ok;
        final String text;
        final Object value;
        /**
         * Whose handle table an expandable result belongs in. Null on failure, where there is no
         * value to expand -- carried on the outcome rather than passed alongside it so the two can
         * never disagree about which thread asked.
         */
        final ThreadState state;

        private Outcome(boolean ok, String text, Object value, ThreadState state) {
            this.ok = ok;
            this.text = text;
            this.value = value;
            this.state = state;
        }

        static Outcome of(ThreadState state, Object value) {
            return new Outcome(true, render(value), value, state);
        }

        static Outcome failed(String reason) {
            return new Outcome(false, reason, null, null);
        }
    }

    /**
     * Answers {@link #CMD_EVALUATE}: runs an expression in one frame's scope.
     *
     * <p>The interpreter does the evaluating, so a watch expression sees exactly what the script
     * sees at that point — its variables, its methods, its imports — rather than a reimplementation
     * of BeanShell's name resolution. Whatever the expression throws comes back as a failed
     * outcome: a mistyped watch is a message in the IDE, not a broken session.
     *
     * <p>Note that {@code Interpreter.eval} returns plain Java, unwrapping the {@code bsh.Primitive}
     * that a namespace lookup would hand back, so the result needs no conversion here.
     */
    private static Outcome evaluate(ThreadState state, int frameId, String expression) {
        Object namespace = frame(state, frameId);
        if (namespace == null) {
            return Outcome.failed("No frame " + frameId + " at this stop");
        }
        if (state.interpreter == null) {
            return Outcome.failed("No interpreter available at this stop");
        }
        try {
            if (interpreterEval == null) {
                interpreterEval = accessible(
                        state.interpreter.getClass().getMethod("eval", String.class, nameSpaceClass));
            }
            return Outcome.of(state, interpreterEval.invoke(state.interpreter, expression, namespace));
        } catch (Throwable t) {
            return Outcome.failed(describe(t, expression));
        }
    }

    /**
     * Answers {@link #CMD_SET_VARIABLE}: evaluates an expression and stores it into {@code handle}.
     *
     * <p>A variable in scope is assigned by evaluating the assignment itself, so BeanShell applies
     * its own rules rather than this code guessing at them: a typed variable refuses an
     * incompatible value exactly as the script would, and a variable inherited from an enclosing
     * scope is updated where it was declared instead of being shadowed here. Anything else — a
     * field, an array element, a list slot, a map entry — is reached reflectively, since there is no
     * expression that names it.
     */
    private static Outcome assign(ThreadState state, int frameId, int handle, String name,
            String expression) {
        if (frame(state, frameId) == null) {
            return Outcome.failed("No frame " + frameId + " at this stop");
        }
        Object target = state.handles.get(Integer.valueOf(handle));
        if (target == null) {
            return Outcome.failed("This value is no longer available");
        }
        if (isNameSpace(target)) {
            Outcome assigned = evaluate(state, frameId, name + " = (" + expression + ")");
            if (!assigned.ok) {
                return assigned;
            }
            // Read the variable back rather than reporting what went in: BeanShell may have coerced
            // it to the declared type, and the IDE should show what is actually stored.
            try {
                return Outcome.of(state, nameSpaceGetVariable.invoke(target, name));
            } catch (Throwable t) {
                return assigned;
            }
        }
        Outcome evaluated = evaluate(state, frameId, expression);
        return evaluated.ok ? store(state, target, name, evaluated.value) : evaluated;
    }

    /** Stores an already-evaluated value into an array element, a list slot, a map entry or a field. */
    @SuppressWarnings("unchecked")
    private static Outcome store(ThreadState state, Object target, String name, Object value) {
        try {
            if (target.getClass().isArray()) {
                int index = indexIn(name);
                if (index < 0 || index >= java.lang.reflect.Array.getLength(target)) {
                    return Outcome.failed("Not an element of this array: " + name);
                }
                java.lang.reflect.Array.set(target, index, value);
                return Outcome.of(state, java.lang.reflect.Array.get(target, index));
            }
            if (target instanceof List) {
                List<Object> list = (List<Object>) target;
                int index = indexIn(name);
                if (index < 0 || index >= list.size()) {
                    return Outcome.failed("Not an element of this list: " + name);
                }
                list.set(index, value);
                return Outcome.of(state, list.get(index));
            }
            if (target instanceof Map) {
                return storeInMap(state, (Map<Object, Object>) target, name, value);
            }
            if (target instanceof Iterable) {
                // The child names here are iteration positions rather than identities, so there is
                // nothing to assign through. Refusing beats writing to whatever happens to sit at
                // that position this time round.
                return Outcome.failed("Elements of a " + simpleName(target) + " cannot be assigned by position");
            }
            Field field = declaredField(target.getClass(), name);
            if (field == null) {
                return Outcome.failed("No field " + name + " on " + simpleName(target));
            }
            field.setAccessible(true);
            field.set(target, value);
            return Outcome.of(state, field.get(target));
        } catch (Throwable t) {
            return Outcome.failed(reason(t));
        }
    }

    /**
     * A map entry is addressed by its key's rendering, because that is the only name the protocol
     * carries. Two keys that render alike are therefore ambiguous, and writing to whichever came
     * first would be a silent guess — so that case is refused rather than resolved.
     */
    private static Outcome storeInMap(ThreadState state, Map<Object, Object> map, String name,
            Object value) {
        Object key = null;
        boolean found = false;
        for (Object candidate : map.keySet()) {
            if (name.equals(String.valueOf(candidate))) {
                if (found) {
                    return Outcome.failed("Ambiguous key: more than one entry renders as " + name);
                }
                key = candidate;
                found = true;
            }
        }
        if (!found) {
            return Outcome.failed("No entry " + name + " in this map");
        }
        // Written through the map rather than through Map.Entry.setValue, which not every
        // implementation honours once iteration has finished.
        map.put(key, value);
        return Outcome.of(state, map.get(key));
    }

    /** Walks outwards exactly as {@link #collectValue} does when it lists the fields. */
    private static Field declaredField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // not at this level; keep walking
            }
        }
        return null;
    }

    /** The index in a synthetic {@code [n]} child name, or -1 when the name is not one. */
    private static int indexIn(String name) {
        if (name.length() < 3 || name.charAt(0) != '[' || name.charAt(name.length() - 1) != ']') {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(1, name.length() - 1));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** Reflection reports the real failure as a cause; the wrapper itself says nothing useful. */
    private static String reason(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause()
                : error;
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getName() : message;
    }

    /**
     * A BeanShell error as one readable line.
     *
     * <p>Its messages lead with the source and an echo of the expression — {@code Sourced file:
     * inline evaluation of: ``x = 1;'' : the part that matters} — and then continue with a script
     * stack trace on further lines. Only the tail of the first line belongs in an IDE error field.
     *
     * <p>The search for the echo's closing quotes starts past the expression, so quotes inside what
     * the user typed cannot split the message in the wrong place. An unrecognised shape falls back
     * to the whole first line, which is verbose rather than wrong.
     */
    private static String describe(Throwable error, String expression) {
        String message = reason(error);
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline);
        }
        int echo = message.indexOf("``");
        if (echo < 0) {
            return message;
        }
        int close = message.indexOf("''", echo + 2 + expression.length());
        if (close < 0) {
            return message;
        }
        String tail = message.substring(close + 2).trim();
        if (tail.startsWith(":")) {
            tail = tail.substring(1).trim();
        }
        return tail.isEmpty() ? message : tail;
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

    /**
     * The type to show for a value.
     *
     * <p>{@code bsh.Primitive} needs unwrapping here for the same reason it is a leaf in
     * {@link #expandable}: it wraps every scripted number and boolean, so its own class name is
     * BeanShell's plumbing rather than anything the user declared. {@code Primitive.NULL} carries no
     * type at all, and {@code null} is the honest label for it.
     */
    private static String typeName(Object value) {
        if (value == null) {
            return "";
        }
        if (PRIMITIVE_CLASS.equals(value.getClass().getName())) {
            Class<?> type = primitiveType(value);
            return type == null ? "null" : type.getSimpleName();
        }
        return simpleName(value);
    }

    private static Class<?> primitiveType(Object primitive) {
        try {
            if (primitiveGetType == null) {
                primitiveGetType = accessible(primitive.getClass().getMethod("getType"));
            }
            return (Class<?>) primitiveGetType.invoke(primitive);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isNameSpace(Object candidate) {
        return candidate != null && nameSpaceClass != null && nameSpaceClass.isInstance(candidate);
    }

    /**
     * Whether a value is a {@code bsh.This} — BeanShell's handle on a namespace.
     *
     * <p>Worth its own test because a {@code This} is never interesting as a Java object. Its Java
     * fields are the interpreter's plumbing; what a debugger user wants is the *namespace* behind it,
     * which is the script's own view: the variables and methods that scope holds. Three separate
     * things in the UI turn out to be this one case — the {@code _bshThis…} field on an instance of a
     * scripted class, the namespace a closure captured, and a {@code This} a script handed back to
     * Java.
     */
    private static boolean isThis(Object candidate) {
        return candidate != null && thisClass != null && thisClass.isInstance(candidate);
    }

    /**
     * The namespace behind a {@code bsh.This}, or null if it cannot be read.
     *
     * <p>{@code This.getNameSpace()} is public, but {@code This} itself is loaded by whichever loader
     * BeanShell came from, so it is reached reflectively like everything else here.
     */
    private static Object thisNameSpace(Object value) {
        try {
            if (thisGetNameSpace == null) {
                thisGetNameSpace = accessible(thisClass.getMethod("getNameSpace"));
            }
            return thisGetNameSpace.invoke(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The interpreter's global namespace, or null when there is no interpreter to ask.
     *
     * <p>Absent on the rewriting path, which is handed a namespace and never an {@code Interpreter}.
     */
    private static Object globalNameSpace(ThreadState state) {
        if (state.interpreter == null) {
            return null;
        }
        try {
            if (interpreterGetGlobalNameSpace == null) {
                interpreterGetGlobalNameSpace =
                        accessible(state.interpreter.getClass().getMethod("getNameSpace"));
            }
            return interpreterGetGlobalNameSpace.invoke(state.interpreter);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Handles are identity-based, so expanding the same object twice does not grow the table. */
    private static int handleFor(ThreadState state, Object value) {
        for (Map.Entry<Integer, Object> entry : state.handles.entrySet()) {
            if (entry.getValue() == value) {
                return entry.getKey().intValue();
            }
        }
        int handle = nextHandle.getAndIncrement();
        state.handles.put(Integer.valueOf(handle), value);
        return handle;
    }

    private static void releaseHandles(ThreadState state) {
        state.handles.clear();
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
        synchronized (CONNECT_LOCK) {
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
                // bsh.This, loaded from BeanShell's own loader rather than named. Optional: without
                // it a This is expanded as a plain Java object, which is worse but not broken.
                try {
                    thisClass = nameSpaceClass.getClassLoader().loadClass("bsh.This");
                } catch (Throwable t) {
                    thisClass = null;
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



    private static String truncate(String value) {
        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH) + "…";
    }

    private static void close() {
        if (channel != null) {
            channel.close();
        }
    }
}
