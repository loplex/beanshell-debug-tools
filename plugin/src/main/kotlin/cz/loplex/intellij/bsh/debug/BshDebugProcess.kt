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
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives a BeanShell debug session. The forked JVM runs the instrumented script
 * whose agent connects back to [server]; each reported statement is either paused
 * (breakpoint / stepping) or released immediately. See [BshDebugProtocol]-style
 * framing in `BshDebugAgent`: `int line, int varCount, (utf name, utf value)*`,
 * and a single byte back to resume.
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
) : XDebugProcess(session) {

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
        releaseAgent()
    }

    private fun releaseAgent() {
        try {
            output?.write(1)
            output?.flush()
        } catch (_: Exception) {
            // connection gone; the process is ending
        }
    }

    private fun readLoop() {
        try {
            val accepted = server.accept()
            socket = accepted
            output = accepted.getOutputStream()
            val input = DataInputStream(accepted.getInputStream())
            while (!stopped) {
                val line = input.readInt()
                val depth = input.readInt()
                val count = input.readInt()
                val variables = LinkedHashMap<String, String>()
                repeat(count) { variables[input.readUTF()] = input.readUTF() }
                handleStep(line, depth, variables)
            }
        } catch (_: Exception) {
            // socket closed or script finished
        }
    }

    private fun handleStep(line: Int, depth: Int, variables: Map<String, String>) {
        currentDepth = depth
        val sourceLine = lineMapper(line)
        val breakpoint = breakpoints[sourceLine]
        val hitRunTo = runToLine == sourceLine
        if (hitRunTo || BshStepLogic.shouldPause(mode, stepDepth, depth, breakpoint != null)) {
            mode = BshStepMode.RUN
            runToLine = null
            val context = BshSuspendContext(sourceLine, variables, sourceFile)
            if (breakpoint != null) session.breakpointReached(breakpoint, null, context)
            else session.positionReached(context)
        } else {
            releaseAgent()
        }
    }

    private inner class BshBreakpointHandler :
        XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(BshLineBreakpointType::class.java) {

        override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
            if (matchesScript(breakpoint)) breakpoints[breakpoint.line + 1] = breakpoint
        }

        override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) {
            if (matchesScript(breakpoint)) breakpoints.remove(breakpoint.line + 1)
        }

        private fun matchesScript(breakpoint: XLineBreakpoint<*>): Boolean =
            breakpoint.fileUrl == sourceFile.url
    }
}
