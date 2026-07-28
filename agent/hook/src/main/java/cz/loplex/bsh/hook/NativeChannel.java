package cz.loplex.bsh.hook;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;

/**
 * Protocol 3: the compact binary transport the IntelliJ plugin speaks.
 *
 * <p>Specified in {@code docs/PROTOCOL.md}. Opcode-tagged in both directions, everything addressed to
 * a thread, replies carrying the id of the request they answer.
 *
 * <p><b>Connects out</b> rather than listening, because the IDE launches the process and is already
 * listening on a port it chose. A configured port with nothing behind it is fatal by design: silently
 * skipping every breakpoint is the failure that looks like "it just ran".
 */
final class NativeChannel implements DebugChannel {

    private static final int CMD_RESUME = 0x01;
    private static final int CMD_SET_BREAKPOINTS = 0x02;
    private static final int CMD_SET_RUN_MODE = 0x03;
    private static final int CMD_SCOPES = 0x04;
    private static final int CMD_VARIABLES = 0x05;
    private static final int CMD_EVALUATE = 0x06;
    private static final int CMD_SET_VARIABLE = 0x07;
    private static final int CMD_SET_CATCH_ALL = 0x08;

    private static final int EVT_STOPPED = 0x10;
    private static final int EVT_SCOPES = 0x11;
    private static final int EVT_VARIABLES = 0x12;
    private static final int EVT_EVALUATED = 0x13;
    private static final int EVT_VARIABLE_SET = 0x14;

    private final int port;

    /** Serialises writes. A message must go out under one acquisition or two threads' fields interleave. */
    private final Object writeLock = new Object();

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;

    NativeChannel(int port) {
        this.port = port;
    }

    public void connect() throws IOException {
        Socket opened = new Socket("127.0.0.1", port);
        opened.setTcpNoDelay(true);
        out = new DataOutputStream(new BufferedOutputStream(opened.getOutputStream(), 8192));
        in = new DataInputStream(opened.getInputStream());
        socket = opened;
    }

    public boolean isConnected() {
        return socket != null;
    }

    public void close() {
        Socket toClose = socket;
        socket = null;
        if (toClose != null) {
            try {
                toClose.close();
            } catch (IOException ignored) {
                // Closing a socket that is already gone is not worth reporting.
            }
        }
    }

    public void sendStopped(int threadId, String threadName, int line, int callDepth, List<Frame> frames)
            throws IOException {
        synchronized (writeLock) {
            out.writeByte(EVT_STOPPED);
            out.writeInt(threadId);
            out.writeUTF(threadName);
            out.writeInt(line);
            out.writeInt(callDepth);
            out.writeInt(frames.size());
            for (int i = 0; i < frames.size(); i++) {
                Frame frame = frames.get(i);
                out.writeUTF(frame.name);
                out.writeUTF(frame.sourceFile);
                out.writeInt(frame.line);
            }
            out.flush();
        }
    }

    public void sendScopes(int requestId, List<Scope> scopes) throws IOException {
        synchronized (writeLock) {
            out.writeByte(EVT_SCOPES);
            out.writeInt(requestId);
            out.writeInt(scopes.size());
            for (int i = 0; i < scopes.size(); i++) {
                out.writeUTF(scopes.get(i).name);
                out.writeInt(scopes.get(i).handle);
            }
            out.flush();
        }
    }

    public void sendVariables(int requestId, List<Variable> variables) throws IOException {
        synchronized (writeLock) {
            out.writeByte(EVT_VARIABLES);
            out.writeInt(requestId);
            out.writeInt(variables.size());
            for (int i = 0; i < variables.size(); i++) {
                Variable variable = variables.get(i);
                out.writeUTF(variable.name);
                out.writeUTF(variable.value);
                out.writeUTF(variable.type);
                out.writeInt(variable.childHandle);
            }
            out.flush();
        }
    }

    public void sendEvaluated(int requestId, boolean setVariable, boolean ok, String value, String type,
            int childHandle) throws IOException {
        synchronized (writeLock) {
            out.writeByte(setVariable ? EVT_VARIABLE_SET : EVT_EVALUATED);
            out.writeInt(requestId);
            out.writeBoolean(ok);
            out.writeUTF(value);
            out.writeUTF(type);
            out.writeInt(childHandle);
            out.flush();
        }
    }

    /**
     * Reads one command.
     *
     * <p>Every opcode's shape has to be known even for the ones this method does not interpret: there
     * are no length prefixes, so an opcode it could not decode would desynchronise the stream for
     * good. An unrecognised one therefore reads its thread id and stops there, which keeps the stream
     * aligned for anything carrying no further fields.
     */
    public Command readCommand() throws IOException {
        int command = in.readByte() & 0xFF;
        if (command == CMD_SET_CATCH_ALL) {
            return Command.mode(Command.Kind.SET_CATCH_ALL, 0, in.readByte());
        }
        if (command == CMD_SET_BREAKPOINTS) {
            int count = in.readInt();
            String[] files = new String[count];
            int[] lines = new int[count];
            for (int i = 0; i < count; i++) {
                files[i] = in.readUTF();
                lines[i] = in.readInt();
            }
            return Command.breakpoints(files, lines);
        }
        int threadId = in.readInt();
        switch (command) {
            case CMD_RESUME:
                return Command.simple(Command.Kind.RESUME, threadId);
            case CMD_SET_RUN_MODE:
                return Command.mode(Command.Kind.SET_RUN_MODE, threadId, in.readByte() & 0xFF);
            case CMD_SCOPES:
                return Command.scopes(threadId, in.readInt(), in.readInt());
            case CMD_VARIABLES:
                return Command.variables(threadId, in.readInt(), in.readInt());
            case CMD_EVALUATE:
                return Command.evaluate(threadId, in.readInt(), in.readInt(), in.readUTF());
            case CMD_SET_VARIABLE:
                return Command.setVariable(threadId, in.readInt(), in.readInt(), in.readInt(),
                        in.readUTF(), in.readUTF());
            default:
                System.err.println("[bsh-agent] ignoring unknown command 0x"
                        + Integer.toHexString(command));
                // Treated as a release: the worst case is a script that keeps running, whereas
                // ignoring it could leave a thread parked for good.
                return Command.simple(Command.Kind.RESUME, threadId);
        }
    }
}
