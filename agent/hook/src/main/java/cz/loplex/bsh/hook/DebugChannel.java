package cz.loplex.bsh.hook;

import java.io.IOException;
import java.util.List;

/**
 * The wire, abstracted: everything the hook needs to say and hear, with no opinion about encoding.
 *
 * <p>This interface is where the two transports part company, and the split is deliberately placed so
 * that <b>nothing interesting lives on the transport side</b>. Deciding what counts as a statement,
 * walking the call stack, rendering values, handing out handles, evaluating in a frame's namespace —
 * all of that produces answers and is written once. Only the last step, turning an answer into
 * bytes, exists twice: {@link NativeChannel} as the compact binary protocol IntelliJ speaks, and
 * {@link DapChannel} as JSON over Content-Length framing for anything else.
 *
 * <p>The abstraction is shaped around the <em>native</em> protocol rather than around DAP, which is
 * the right way round even though DAP is the standard: the native one is the narrower of the two, so
 * everything it needs DAP can express, while the reverse is not true. Where DAP wants something the
 * hook does not track — a source reference, a variable's {@code evaluateName} — the DAP channel
 * synthesises it rather than the hook learning about it.
 */
interface DebugChannel {

    /**
     * Establishes the connection, blocking until there is a client.
     *
     * <p>The two transports differ in direction, which is not incidental. The native channel
     * <b>connects out</b> to a port the IDE is already listening on, because the IDE launches the
     * process and knows the port before it exists. A DAP client instead expects to <b>attach</b> to
     * something already running, so the DAP channel listens.
     */
    void connect() throws IOException;

    /** Whether a client has been seen. Nothing is reported before then. */
    boolean isConnected();

    void close();

    /**
     * Announces that a thread has suspended at a statement.
     *
     * <p>Called with the thread's frames already collected, because the collecting is the part both
     * transports share.
     */
    void sendStopped(int threadId, String threadName, int line, int callDepth, List<Frame> frames)
            throws IOException;

    /** Answers a scopes request. */
    void sendScopes(int requestId, List<Scope> scopes) throws IOException;

    /** Answers a variables request. */
    void sendVariables(int requestId, List<Variable> variables) throws IOException;

    /**
     * Answers an evaluate or set-variable request.
     *
     * @param setVariable true when answering a set rather than an evaluate; the native protocol uses
     *     a distinct opcode for each, and DAP a distinct command name
     */
    void sendEvaluated(int requestId, boolean setVariable, boolean ok, String value, String type,
            int childHandle) throws IOException;

    /**
     * Blocks until the next command arrives, or returns null at end of stream.
     *
     * <p>Called only from the hook's single reader thread, so it needs no synchronisation of its own.
     */
    Command readCommand() throws IOException;

    /** One frame of a reported stack. */
    final class Frame {
        final String name;
        final String sourceFile;
        final int line;

        Frame(String name, String sourceFile, int line) {
            this.name = name;
            this.sourceFile = sourceFile;
            this.line = line;
        }
    }

    /** One scope of a frame — "Locals", "Global". */
    final class Scope {
        final String name;
        final int handle;

        Scope(String name, int handle) {
            this.name = name;
            this.handle = handle;
        }
    }

    /** One variable, rendered. [childHandle] is 0 when there is nothing to expand. */
    final class Variable {
        final String name;
        final String value;
        final String type;
        final int childHandle;

        Variable(String name, String value, String type, int childHandle) {
            this.name = name;
            this.value = value;
            this.type = type;
            this.childHandle = childHandle;
        }
    }

    /**
     * A command, normalised across the two encodings.
     *
     * <p>Deliberately one flat type rather than a hierarchy: there are seven of them, each carrying at
     * most a handful of ints and a string or two, and the hook switches on {@link #kind} in one place.
     * A class per command would be more Java and less readable.
     */
    final class Command {

        /** What the command is. Names rather than opcodes, so both transports can produce them. */
        enum Kind {
            RESUME,
            SET_BREAKPOINTS,
            SET_RUN_MODE,
            SET_CATCH_ALL,
            SCOPES,
            VARIABLES,
            EVALUATE,
            SET_VARIABLE,
            /** The client went away, or asked to. Everything parked must be released. */
            DISCONNECT,
            /** Something the transport handled itself (a DAP handshake message). Ignore it. */
            HANDLED
        }

        final Kind kind;
        /** Which thread, or 0 for the commands that are global. */
        final int threadId;
        /** Correlates a reply with its request; 0 where no reply is expected. */
        final int requestId;
        final int frameId;
        final int handle;
        final String name;
        final String expression;
        /** For SET_RUN_MODE and SET_CATCH_ALL. */
        final int mode;
        /** For SET_BREAKPOINTS: parallel arrays of file and line. */
        final String[] breakpointFiles;
        final int[] breakpointLines;

        private Command(Kind kind, int threadId, int requestId, int frameId, int handle, String name,
                String expression, int mode, String[] breakpointFiles, int[] breakpointLines) {
            this.kind = kind;
            this.threadId = threadId;
            this.requestId = requestId;
            this.frameId = frameId;
            this.handle = handle;
            this.name = name;
            this.expression = expression;
            this.mode = mode;
            this.breakpointFiles = breakpointFiles;
            this.breakpointLines = breakpointLines;
        }

        static Command simple(Kind kind, int threadId) {
            return new Command(kind, threadId, 0, 0, 0, null, null, 0, null, null);
        }

        static Command mode(Kind kind, int threadId, int mode) {
            return new Command(kind, threadId, 0, 0, 0, null, null, mode, null, null);
        }

        static Command breakpoints(String[] files, int[] lines) {
            return new Command(Kind.SET_BREAKPOINTS, 0, 0, 0, 0, null, null, 0, files, lines);
        }

        static Command scopes(int threadId, int requestId, int frameId) {
            return new Command(Kind.SCOPES, threadId, requestId, frameId, 0, null, null, 0, null, null);
        }

        static Command variables(int threadId, int requestId, int handle) {
            return new Command(Kind.VARIABLES, threadId, requestId, 0, handle, null, null, 0, null, null);
        }

        static Command evaluate(int threadId, int requestId, int frameId, String expression) {
            return new Command(Kind.EVALUATE, threadId, requestId, frameId, 0, null, expression, 0,
                    null, null);
        }

        static Command setVariable(int threadId, int requestId, int frameId, int handle, String name,
                String expression) {
            return new Command(Kind.SET_VARIABLE, threadId, requestId, frameId, handle, name,
                    expression, 0, null, null);
        }
    }
}
