package cz.loplex.intellij.bsh.debug

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.SuspendPolicy
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
import java.util.concurrent.atomic.AtomicInteger

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
     * Maps a position the agent reported — its own source name and line — to a 1-based line in
     * [sourceFile], or -1 when that source is not part of [sourceFile] at all.
     *
     * Null means the reported lines already *are* [sourceFile] lines: the standalone `.bsh` file,
     * and the rewriting Maven path, whose injected hook calls carry pom.xml lines baked in.
     *
     * The Maven path under the instrumenting agent supplies one: there BeanShell reports lines
     * relative to the snippet it was handed, under the synthetic name it derives from the script's
     * own text, so both halves are needed — the name says *which* inline script, the line says
     * where in it.
     */
    private val lineMapper: ((sourceFile: String, line: Int) -> Int)? = null,
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
    /**
     * A line printed to the console once the session is up, or null for the ordinary case.
     *
     * Exists for one message: that the agent was unavailable and the session is the rewriting one.
     * It has to be said out loud because it cannot be seen — see [sessionInitialized].
     */
    private val startupNotice: String? = null,
) : XDebugProcess(session), BshValueSource {

    private val editorsProvider = BshDebuggerEditorsProvider()
    private val breakpoints = ConcurrentHashMap<Int, XLineBreakpoint<*>>()
    private val handler = BshBreakpointHandler()

    /**
     * Everything that used to be one scalar for the whole session, now per script thread.
     *
     * Stepping is the reason: `mode` and `stepDepth` decide whether the *next* statement pauses, and
     * with one copy between them a Step Over on one thread would change where another stops. The
     * frames are kept too, so a thread suspended while the user looks at a different one can still
     * populate the Threads combo without being resumed first.
     */
    private class ThreadSession(val id: Int, val name: String) {
        @Volatile var mode = BshStepMode.RUN
        @Volatile var stepDepth = 0
        @Volatile var currentDepth = 0
        @Volatile var frames: List<BshFrameInfo> = emptyList()
        @Volatile var suspended = false
        /** One-shot "Run to Cursor" target, in [sourceFile] lines (1-based); null when inactive. */
        @Volatile var runToLine: Int? = null
    }

    private val threads = ConcurrentHashMap<Int, ThreadSession>()

    /**
     * The thread the user's last action applied to.
     *
     * Resume and the step commands arrive from the platform with an `XSuspendContext`, whose active
     * execution stack says which thread is selected -- but the platform may also pass null, so the
     * thread that reported the stop being looked at is remembered as the fallback.
     */
    @Volatile private var lastStoppedThread: Int = 0

    /**
     * Whether the current stop is a Suspend: All one, so arriving threads are held rather than let go.
     *
     * Read from the breakpoint that was hit — IntelliJ already offers the choice in the breakpoint's
     * own properties, so there is nothing new for the user to learn and no setting of ours to invent.
     */
    @Volatile private var catchAll = false

    @Volatile private var stopped = false
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var commands: DataOutputStream? = null

    /**
     * Replies waiting to be collected, keyed by the request id they answer.
     *
     * Protocol 2 needed none of this: a reply was always the next thing on the wire, so one
     * capacity-one queue and a lock sufficed. That stops holding once two threads can be suspended —
     * thread B may report a stop, or have its own request answered, between A's request and A's
     * answer. Correlating by id also retires the old hazard for good: a timed-out request no longer
     * poisons the channel, because its late reply can only match an id nobody is waiting on.
     */
    private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<Any>>()
    private val nextRequestId = AtomicInteger(1)

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
        // After startNotify, or the console is not listening yet and the text is dropped. This is the
        // only place the user can be told the session is a degraded one -- the frames of a rewritten
        // script carry correct line numbers, so nothing else about the UI gives it away.
        startupNotice?.let { processHandler.notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }
    }

    override fun resume(context: XSuspendContext?) = proceed(context, BshStepMode.RUN)
    override fun startStepInto(context: XSuspendContext?) = proceed(context, BshStepMode.INTO)
    override fun startStepOver(context: XSuspendContext?) = proceed(context, BshStepMode.OVER)
    override fun startStepOut(context: XSuspendContext?) = proceed(context, BshStepMode.OUT)

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        // A one-shot line target: run freely until the agent reports that line in this script.
        val session = sessionOf(context) ?: return
        session.runToLine = if (position.file == sourceFile) position.line + 1 else null
        proceed(context, BshStepMode.RUN)
    }

    /**
     * Which thread a platform action applies to.
     *
     * The suspend context names it, since the user picks a thread in the Threads combo. A null
     * context (the platform does allow it) falls back to whichever thread reported the stop being
     * looked at, which is the same thread in every single-threaded session.
     */
    private fun sessionOf(context: XSuspendContext?): ThreadSession? {
        val fromContext = (context?.activeExecutionStack as? BshThreadStack)?.threadId
        return threads[fromContext ?: lastStoppedThread]
    }

    override fun stop() {
        stopped = true
        runCatching { socket?.close() }
        runCatching { server.close() }
    }

    private fun proceed(context: XSuspendContext?, newMode: BshStepMode) {
        val session = sessionOf(context) ?: return
        session.mode = newMode
        session.stepDepth = session.currentDepth
        // Tell the agent what to filter before letting it go, so it applies from the very next
        // statement rather than one stop late.
        pushFilter(session)
        if (catchAll) {
            // Suspend: All is symmetric -- if the stop meant "everything stops", the resume has to
            // mean "everything runs", or the threads rounded up would stay parked with nothing in
            // the UI to release them.
            catchAll = false
            setCatchAll(false)
            for (other in threads.values.filter { it.suspended && it.id != session.id }) {
                releaseAgent(other.id)
            }
        }
        releaseAgent(session.id)
    }

    private fun releaseAgent(threadId: Int) {
        threads[threadId]?.suspended = false
        writeToAgent {
            it.writeByte(CMD_RESUME)
            it.writeInt(threadId)
        }
    }

    /**
     * Hands the agent the breakpoint set and the current run mode.
     *
     * While stepping the agent must report every statement, because [BshStepLogic] owns that
     * decision; only plain running can be filtered. A "Run to Cursor" target is pushed as an extra
     * breakpoint -- without that it would never be reached, since a filtering agent would not
     * report the line.
     */
    private fun pushFilter(session: ThreadSession?) {
        if (!pushFilterToAgent) return
        val lines = breakpoints.keys.toMutableSet()
        session?.runToLine?.let { lines.add(it) }
        val path = sourceFile.path
        writeToAgent { out ->
            // Breakpoints are shared by every thread, so they carry no thread id; the run mode is
            // per thread, because stepping one thread must not make the others report every
            // statement as well.
            out.writeByte(CMD_SET_BREAKPOINTS)
            out.writeInt(lines.size)
            for (line in lines) {
                out.writeUTF(path)
                out.writeInt(line)
            }
            if (session != null) {
                out.writeByte(CMD_SET_RUN_MODE)
                out.writeInt(session.id)
                out.writeByte(if (session.mode == BshStepMode.RUN) MODE_RUN else MODE_STEPPING)
            }
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
            pushFilter(null)
            val input = DataInputStream(accepted.getInputStream())
            while (!stopped) {
                when (input.readByte().toInt() and 0xFF) {
                    EVT_STOPPED -> readStopped(input)
                    // Every reply leads with the id of the request it answers, so it can be handed to
                    // whoever is waiting for that one rather than to whoever asked most recently.
                    EVT_SCOPES -> deliver(input.readInt(), readScopesReply(input))
                    EVT_VARIABLES -> deliver(input.readInt(), readVariablesReply(input))
                    EVT_EVALUATED, EVT_VARIABLE_SET -> deliver(input.readInt(), readEvalReply(input))
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
        val threadId = input.readInt()
        val threadName = input.readUTF()
        val line = input.readInt()
        val depth = input.readInt()
        val frames = (0 until input.readInt()).map { index ->
            BshFrameInfo(index, input.readUTF(), input.readUTF(), input.readInt())
        }
        val session = threads.computeIfAbsent(threadId) { ThreadSession(threadId, threadName) }
        handleStep(session, line, depth, frames)
    }

    /**
     * Hands a reply to the request that is waiting for it, or drops it.
     *
     * A reply with nobody waiting is normal rather than an error: the request timed out and gave up.
     * Dropping it here is exactly what protocol 2 could not do — without ids, that late reply would
     * have been collected by the next request as its own answer.
     */
    private fun deliver(requestId: Int, reply: Any) {
        pending.remove(requestId)?.offer(reply)
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

    override fun scopes(threadId: Int, frameId: Int): List<Pair<String, Int>> = (
        exchange(REQUEST_TIMEOUT_MS) { out, requestId ->
            out.writeByte(CMD_SCOPES)
            out.writeInt(threadId)
            out.writeInt(requestId)
            out.writeInt(frameId)
        } as? ScopesReply
        )?.scopes.orEmpty()

    override fun variables(threadId: Int, handle: Int): List<BshVariable> = (
        exchange(REQUEST_TIMEOUT_MS) { out, requestId ->
            out.writeByte(CMD_VARIABLES)
            out.writeInt(threadId)
            out.writeInt(requestId)
            out.writeInt(handle)
        } as? VariablesReply
        )?.variables.orEmpty()

    override fun evaluate(threadId: Int, frameId: Int, expression: String): BshEvalResult? =
        exchange(EVAL_TIMEOUT_MS) { out, requestId ->
            out.writeByte(CMD_EVALUATE)
            out.writeInt(threadId)
            out.writeInt(requestId)
            out.writeInt(frameId)
            out.writeUTF(expression)
        } as? BshEvalResult

    override fun setVariable(
        threadId: Int,
        frameId: Int,
        containerHandle: Int,
        name: String,
        expression: String,
    ): BshEvalResult? = exchange(EVAL_TIMEOUT_MS) { out, requestId ->
        out.writeByte(CMD_SET_VARIABLE)
        out.writeInt(threadId)
        out.writeInt(requestId)
        out.writeInt(frameId)
        out.writeInt(containerHandle)
        out.writeUTF(name)
        out.writeUTF(expression)
    } as? BshEvalResult

    /**
     * Sends one request and waits for the reply that carries its id.
     *
     * No longer serialised, and that is the point: two threads may be suspended, so the variables
     * panel for one and a watch expression on another can legitimately be in flight at the same time.
     * Each caller registers a queue under its own request id, and [deliver] wakes exactly that one.
     *
     * A timeout is now merely a lost answer. It used to be worse — replies were matched by arrival
     * order, so a late one would have been handed to the *next* request as its own, which is why the
     * channel had to be written off permanently. With ids, the late reply finds no waiter and is
     * dropped.
     */
    private fun exchange(timeoutMs: Long, write: (DataOutputStream, Int) -> Unit): Any? {
        val out = commands ?: return null
        val requestId = nextRequestId.getAndIncrement()
        val waiter = ArrayBlockingQueue<Any>(1)
        pending[requestId] = waiter
        try {
            synchronized(out) {
                write(out, requestId)
                out.flush()
            }
        } catch (_: Exception) {
            pending.remove(requestId)
            return null
        }
        val reply = waiter.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (reply == null) pending.remove(requestId)
        return reply
    }

    private fun handleStep(thread: ThreadSession, line: Int, depth: Int, frames: List<BshFrameInfo>) {
        thread.currentDepth = depth
        val reportedSource = frames.firstOrNull()?.sourceFile ?: ""
        val sourceLine = lineMapper?.invoke(reportedSource, line) ?: line
        val breakpoint = breakpoints[sourceLine]
        val hitRunTo = thread.runToLine == sourceLine
        if (hitRunTo || BshStepLogic.shouldPause(thread.mode, thread.stepDepth, depth, breakpoint != null)) {
            thread.mode = BshStepMode.RUN
            thread.runToLine = null
            thread.frames = frames
            thread.suspended = true
            lastStoppedThread = thread.id
            // A Suspend: All breakpoint rounds up the other threads: they are told to report their
            // next statement, and the branch below holds each one as it arrives.
            if (breakpoint != null && breakpoint.suspendPolicy == SuspendPolicy.ALL) {
                catchAll = true
                setCatchAll(true)
            }
            val context = suspendContext(thread)
            if (breakpoint != null) session.breakpointReached(breakpoint, null, context)
            else session.positionReached(context)
        } else if (catchAll) {
            // Rounded up rather than stopped in its own right: this thread reported only because a
            // Suspend: All breakpoint is in force elsewhere. It is parked and listed in the Threads
            // combo, but the platform is not told again -- doing so would move the user's selection
            // off the thread they are actually reading.
            thread.frames = frames
            thread.suspended = true
        } else {
            releaseAgent(thread.id)
        }
    }

    /** Turns the agent's report-everything mode on or off; see the hook's `catchAll`. */
    private fun setCatchAll(on: Boolean) {
        writeToAgent {
            it.writeByte(CMD_SET_CATCH_ALL)
            it.writeByte(if (on) 1 else 0)
        }
    }

    /**
     * The context for a stop: the thread that reported it, plus every other thread still suspended.
     *
     * Listing the others is what puts them in the Threads combo. They are genuinely inspectable while
     * listed — the agent parks each thread on its own mailbox and keeps answering questions about it,
     * so selecting one does not require resuming this one first.
     */
    private fun suspendContext(active: ThreadSession): BshSuspendContext {
        val stacks = threads.values
            .filter { it.suspended }
            .sortedBy { it.id }
            .map { stackFor(it) }
        val activeStack = stacks.firstOrNull { it.threadId == active.id } ?: stackFor(active)
        return BshSuspendContext(activeStack, stacks.ifEmpty { listOf(activeStack) })
    }

    private fun stackFor(thread: ThreadSession): BshThreadStack =
        BshThreadStack(thread.id, thread.name, thread.frames, sourceFile, ::frameLine, this)

    /**
     * Where a frame sits in [sourceFile], or -1 when it sits somewhere else.
     *
     * With a [lineMapper] the decision is entirely its own — it knows the reported source names and
     * answers -1 for anything foreign, so the injected-pom case needs no name matching here.
     *
     * Without one, the innermost frame is taken on trust (the agent's own source filter, or a
     * rewritten script, already guarantees it is ours) while outer frames must be shown to be in
     * this file: a `source()`d script or a frame entered from Java has no position in it.
     */
    private fun frameLine(frame: BshFrameInfo): Int {
        lineMapper?.let { return it(frame.sourceFile, frame.line) }
        return when {
            frame.id == 0 -> frame.line
            frame.line >= 1 && inSourceFile(frame.sourceFile) -> frame.line
            else -> -1
        }
    }

    private fun inSourceFile(reported: String): Boolean =
        reported.isNotEmpty() && (reported.endsWith(sourceFile.name) || sourceFile.path.endsWith(reported))

    private inner class BshBreakpointHandler :
        XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(BshLineBreakpointType::class.java) {

        override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
            if (!matchesScript(breakpoint)) return
            breakpoints[breakpoint.line + 1] = breakpoint
            // Picked up mid-run: each thread drains its mailbox at every statement it does not
            // report, so a breakpoint added while the script runs takes effect without a stop.
            // Null session: the breakpoint set is shared, and no thread's run mode is being changed.
            pushFilter(null)
        }

        override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) {
            if (!matchesScript(breakpoint)) return
            breakpoints.remove(breakpoint.line + 1)
            pushFilter(null)
        }

        private fun matchesScript(breakpoint: XLineBreakpoint<*>): Boolean =
            breakpoint.fileUrl == sourceFile.url
    }
}
