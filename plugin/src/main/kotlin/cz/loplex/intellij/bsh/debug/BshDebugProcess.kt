package cz.loplex.intellij.bsh.debug

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * How long a scopes or variables request waits before giving up.
 *
 * Only reachable if the agent died between the stop and the expansion, since it answers from the
 * loop it is already parked in. Bounded anyway: the platform calls `computeChildren` off the UI
 * thread, but an unbounded wait would still leak a stuck thread per expansion.
 */
private const val REQUEST_TIMEOUT_MS = 5_000L

/**
 * How long an evaluation waits, which cannot be the same bound.
 *
 * A watch expression is arbitrary user code — it may call a script method that does real work — so a
 * few seconds is a plausible answer rather than a failure. This one only has to be long enough that
 * firing it really does mean something is wrong.
 */
private const val EVAL_TIMEOUT_MS = 30_000L

/**
 * Drives a BeanShell debug session. The forked JVM runs the script -- rewritten with hook calls,
 * or untouched under the instrumenting agent, see [BshInstrumentationMode] -- whose agent connects
 * back to [server]; each reported statement is either paused (breakpoint / stepping) or released
 * immediately.
 *
 * Framing, agent to IDE: [EVT_STOPPED] `int line, int callDepth, int frameCount,
 * (utf name, utf sourceFile, int line)*`, plus [EVT_SCOPES], [EVT_VARIABLES], [EVT_EVALUATED] and
 * [EVT_VARIABLE_SET] answering a request. IDE to agent: [CMD_RESUME], optionally preceded by
 * [CMD_SET_BREAKPOINTS] and [CMD_SET_RUN_MODE] when [pushFilterToAgent] lets the agent filter for
 * itself, and [CMD_SCOPES] / [CMD_VARIABLES] / [CMD_EVALUATE] / [CMD_SET_VARIABLE] while suspended.
 * The full table is in `BshDebugProtocol.kt`.
 *
 * Variables are pulled rather than pushed. The agent hands out an opaque handle per expandable
 * value, valid only until the next resume, which is what lets a nested object be opened one level
 * at a time instead of every variable being serialised on every step. It is deliberately DAP's
 * `variablesReference` model, so adopting DAP later is a change of encoding rather than of design.
 */
class BshDebugProcess(
    session: XDebugSession,
    private val processHandler: ProcessHandler,
    private val server: ServerSocket,
    private val sourceFile: VirtualFile,
    /**
     * Whether [sessionInitialized] should call `startNotify` on the process handler. True when the
     * session is created via `startSession` (the `.bsh` path — the platform does not start it for us);
     * false when created via `startSessionAndShowTab` (the Maven path — the platform already starts it,
     * so calling it here first would make the platform's own call fail with "startNotify called already").
     */
    private val notifyStartOnInit: Boolean = true,
    /**
     * Maps a line reported by the agent to a 1-based line in [sourceFile]. Identity for a
     * standalone `.bsh` file; for an inline script injected into a pom.xml it translates the
     * snippet-relative line to the host pom.xml line.
     */
    private val lineMapper: (Int) -> Int = { it },
    /**
     * Whether to hand the agent its breakpoint set so it can filter locally instead of reporting
     * every statement and waiting for a verdict.
     *
     * Only valid when [lineMapper] is the identity: filtering needs breakpoints expressed in the
     * lines the agent reports, and there is no inverse of a non-trivial mapping. So the standalone
     * `.bsh` path enables it and the injected-pom path does not, where the agent simply keeps
     * reporting everything as before.
     */
    private val pushFilterToAgent: Boolean = false,
    /**
     * Whether the agent on the other end can evaluate expressions, which decides whether the UI
     * offers Watches and Set Value. True under the instrumenting agent, which holds the
     * `Interpreter`; false on the rewriting path, which is handed only a `NameSpace`.
     */
    override val supportsEvaluation: Boolean = false,
) : XDebugProcess(session), BshValueSource {

    private val editorsProvider = BshDebuggerEditorsProvider()
    private val breakpoints = ConcurrentHashMap<Int, XLineBreakpoint<*>>()
    private val handler = BshBreakpointHandler()

    @Volatile private var mode = BshStepMode.RUN
    @Volatile private var stepDepth = 0
    @Volatile private var currentDepth = 0
    @Volatile private var stopped = false
    /** One-shot "Run to Cursor" target, in [sourceFile] line coordinates (1-based); null when inactive. */
    @Volatile private var runToLine: Int? = null
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var commands: DataOutputStream? = null

    /**
     * Hands a reply from the reader thread to whichever thread asked for it.
     *
     * Capacity one, guarded by [requestLock], because only one request is ever in flight: the
     * agent answers from inside the loop where it waits to be resumed, so replies arrive in the
     * order the requests were sent and nothing else can appear between them.
     */
    private val responses = ArrayBlockingQueue<Any>(1)
    private val requestLock = Any()

    /** Set once a request went unanswered, after which no reply can be placed. See [exchange]. */
    @Volatile private var desynced = false

    override fun createConsole(): ExecutionConsole {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(session.project).console
        console.attachToProcess(processHandler)
        return console
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(handler)

    override fun doGetProcessHandler(): ProcessHandler = processHandler

    override fun sessionInitialized() {
        // Start reading the agent only now: the platform has registered breakpoints by this point,
        // so a fast script (e.g. an enforcer <condition> that runs the instant Maven starts) cannot
        // stream past them before they exist. Start it before startNotify so accept() is ready when
        // the .bsh path launches its process.
        Thread({ readLoop() }, "bsh-debug-reader").apply { isDaemon = true }.start()
        // startSession() does not start the process, so we must; startSessionAndShowTab() already did,
        // so we must not (its own startNotify would then fail with "startNotify called already").
        if (notifyStartOnInit && !processHandler.isStartNotified) processHandler.startNotify()
    }

    override fun resume(context: XSuspendContext?) = proceed(BshStepMode.RUN)
    override fun startStepInto(context: XSuspendContext?) = proceed(BshStepMode.INTO)
    override fun startStepOver(context: XSuspendContext?) = proceed(BshStepMode.OVER)
    override fun startStepOut(context: XSuspendContext?) = proceed(BshStepMode.OUT)

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        // A one-shot line target: run freely until the agent reports that line in this script.
        runToLine = if (position.file == sourceFile) position.line + 1 else null
        proceed(BshStepMode.RUN)
    }

    override fun stop() {
        stopped = true
        runCatching { socket?.close() }
        runCatching { server.close() }
    }

    private fun proceed(newMode: BshStepMode) {
        mode = newMode
        stepDepth = currentDepth
        // Tell the agent what to filter before letting it go, so it applies from the very next
        // statement rather than one stop late.
        pushFilter()
        releaseAgent()
    }

    private fun releaseAgent() {
        writeToAgent { it.writeByte(CMD_RESUME) }
    }

    /**
     * Hands the agent the breakpoint set and the current run mode.
     *
     * While stepping the agent must report every statement, because [BshStepLogic] owns that
     * decision; only plain running can be filtered. A "Run to Cursor" target is pushed as an extra
     * breakpoint -- without that it would never be reached, since a filtering agent would not
     * report the line.
     */
    private fun pushFilter() {
        if (!pushFilterToAgent) return
        val lines = breakpoints.keys.toMutableSet()
        runToLine?.let { lines.add(it) }
        val path = sourceFile.path
        writeToAgent { out ->
            out.writeByte(CMD_SET_BREAKPOINTS)
            out.writeInt(lines.size)
            for (line in lines) {
                out.writeUTF(path)
                out.writeInt(line)
            }
            out.writeByte(CMD_SET_RUN_MODE)
            out.writeByte(if (mode == BshStepMode.RUN) MODE_RUN else MODE_STEPPING)
        }
    }

    /** Serialises writes: the platform calls resume/step and breakpoint changes from any thread. */
    private fun writeToAgent(write: (DataOutputStream) -> Unit) {
        val out = commands ?: return
        try {
            synchronized(out) {
                write(out)
                out.flush()
            }
        } catch (_: Exception) {
            // connection gone; the process is ending
        }
    }

    private fun readLoop() {
        try {
            val accepted = server.accept()
            socket = accepted
            output = accepted.getOutputStream()
            commands = DataOutputStream(accepted.getOutputStream())
            // The agent reports everything until it is told otherwise, so push the initial set as
            // soon as the connection exists. The very first statement still arrives unfiltered:
            // the agent opens the connection on its first report and cannot know sooner.
            pushFilter()
            val input = DataInputStream(accepted.getInputStream())
            while (!stopped) {
                when (input.readByte().toInt() and 0xFF) {
                    EVT_STOPPED -> readStopped(input)
                    EVT_SCOPES -> responses.offer(readScopesReply(input))
                    EVT_VARIABLES -> responses.offer(readVariablesReply(input))
                    EVT_EVALUATED, EVT_VARIABLE_SET -> responses.offer(readEvalReply(input))
                    // An opcode we do not know means the stream is no longer framed the way we
                    // think it is, and every later read would be garbage. Stop rather than guess.
                    else -> return
                }
            }
        } catch (_: Exception) {
            // socket closed or script finished
        }
    }

    private fun readStopped(input: DataInputStream) {
        val line = input.readInt()
        val depth = input.readInt()
        val frames = (0 until input.readInt()).map { index ->
            BshFrameInfo(index, input.readUTF(), input.readUTF(), input.readInt())
        }
        handleStep(line, depth, frames)
    }

    private fun readScopesReply(input: DataInputStream): ScopesReply =
        ScopesReply((0 until input.readInt()).map { input.readUTF() to input.readInt() })

    private fun readVariablesReply(input: DataInputStream): VariablesReply =
        VariablesReply(
            (0 until input.readInt()).map {
                BshVariable(input.readUTF(), input.readUTF(), input.readUTF(), input.readInt())
            },
        )

    private fun readEvalReply(input: DataInputStream): BshEvalResult =
        BshEvalResult(input.readBoolean(), input.readUTF(), input.readUTF(), input.readInt())

    private class ScopesReply(val scopes: List<Pair<String, Int>>)
    private class VariablesReply(val variables: List<BshVariable>)

    override fun scopes(frameId: Int): List<Pair<String, Int>> = (
        exchange({ it.writeByte(CMD_SCOPES); it.writeInt(frameId) }, REQUEST_TIMEOUT_MS) as? ScopesReply
        )?.scopes.orEmpty()

    override fun variables(handle: Int): List<BshVariable> = (
        exchange({ it.writeByte(CMD_VARIABLES); it.writeInt(handle) }, REQUEST_TIMEOUT_MS) as? VariablesReply
        )?.variables.orEmpty()

    override fun evaluate(frameId: Int, expression: String): BshEvalResult? = exchange(
        {
            it.writeByte(CMD_EVALUATE)
            it.writeInt(frameId)
            it.writeUTF(expression)
        },
        EVAL_TIMEOUT_MS,
    ) as? BshEvalResult

    override fun setVariable(
        frameId: Int,
        containerHandle: Int,
        name: String,
        expression: String,
    ): BshEvalResult? = exchange(
        {
            it.writeByte(CMD_SET_VARIABLE)
            it.writeInt(frameId)
            it.writeInt(containerHandle)
            it.writeUTF(name)
            it.writeUTF(expression)
        },
        EVAL_TIMEOUT_MS,
    ) as? BshEvalResult

    /**
     * Sends one request and waits for its reply. Serialised: one request is in flight at a time.
     *
     * Replies are matched by arrival order and carry no request id, which is sound only as long as
     * every request is answered. A timeout breaks that: the agent may still be working, and its
     * reply would then arrive while a later request is waiting and be handed over as that request's
     * answer. So a timeout retires the request channel for good — later calls fail fast rather than
     * risk returning the right-looking answer to the wrong question. Correlating replies is the
     * general fix and it belongs with threads, which need it anyway.
     */
    private fun exchange(write: (DataOutputStream) -> Unit, timeoutMs: Long): Any? = synchronized(requestLock) {
        if (desynced) return null
        responses.clear()
        val out = commands ?: return null
        try {
            synchronized(out) {
                write(out)
                out.flush()
            }
        } catch (_: Exception) {
            return null
        }
        val reply = responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (reply == null) desynced = true
        return reply
    }

    private fun handleStep(line: Int, depth: Int, frames: List<BshFrameInfo>) {
        currentDepth = depth
        val sourceLine = lineMapper(line)
        val breakpoint = breakpoints[sourceLine]
        val hitRunTo = runToLine == sourceLine
        if (hitRunTo || BshStepLogic.shouldPause(mode, stepDepth, depth, breakpoint != null)) {
            mode = BshStepMode.RUN
            runToLine = null
            val context = BshSuspendContext(frames, sourceFile, ::frameLine, this)
            if (breakpoint != null) session.breakpointReached(breakpoint, null, context)
            else session.positionReached(context)
        } else {
            releaseAgent()
        }
    }

    /**
     * Where a frame sits in [sourceFile], or -1 when it sits somewhere else.
     *
     * The innermost frame is always mapped, which is what keeps the injected-pom path working:
     * there [lineMapper] translates a snippet line to a pom.xml line, and the agent reports the
     * snippet's own name for the file. Outer frames are only mapped when they really are in this
     * file -- a `source()`d script or the frame entered from Java has no position here.
     */
    private fun frameLine(frame: BshFrameInfo): Int = when {
        frame.id == 0 -> lineMapper(frame.line)
        frame.line >= 1 && inSourceFile(frame.sourceFile) -> lineMapper(frame.line)
        else -> -1
    }

    private fun inSourceFile(reported: String): Boolean =
        reported.isNotEmpty() && (reported.endsWith(sourceFile.name) || sourceFile.path.endsWith(reported))

    private inner class BshBreakpointHandler :
        XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(BshLineBreakpointType::class.java) {

        override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
            if (!matchesScript(breakpoint)) return
            breakpoints[breakpoint.line + 1] = breakpoint
            // Picked up mid-run: the agent drains pending commands at each statement, so a
            // breakpoint added while the script runs takes effect without a stop.
            pushFilter()
        }

        override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) {
            if (!matchesScript(breakpoint)) return
            breakpoints.remove(breakpoint.line + 1)
            pushFilter()
        }

        private fun matchesScript(breakpoint: XLineBreakpoint<*>): Boolean =
            breakpoint.fileUrl == sourceFile.url
    }
}
