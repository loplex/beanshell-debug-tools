package cz.loplex.intellij.bsh.debug

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
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
    private val scriptFile: VirtualFile,
) : XDebugProcess(session) {

    private val editorsProvider = BshDebuggerEditorsProvider()
    private val breakpoints = ConcurrentHashMap<Int, XLineBreakpoint<*>>()
    private val handler = BshBreakpointHandler()

    @Volatile private var mode = BshStepMode.RUN
    @Volatile private var stepDepth = 0
    @Volatile private var currentDepth = 0
    @Volatile private var stopped = false
    private var socket: Socket? = null
    private var output: OutputStream? = null

    init {
        Thread({ readLoop() }, "bsh-debug-reader").apply { isDaemon = true }.start()
    }

    override fun createConsole(): ExecutionConsole {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(session.project).console
        console.attachToProcess(processHandler)
        return console
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(handler)

    override fun doGetProcessHandler(): ProcessHandler = processHandler

    override fun sessionInitialized() {
        processHandler.startNotify()
    }

    override fun resume(context: XSuspendContext?) = proceed(BshStepMode.RUN)
    override fun startStepInto(context: XSuspendContext?) = proceed(BshStepMode.INTO)
    override fun startStepOver(context: XSuspendContext?) = proceed(BshStepMode.OVER)
    override fun startStepOut(context: XSuspendContext?) = proceed(BshStepMode.OUT)

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
        val breakpoint = breakpoints[line]
        if (BshStepLogic.shouldPause(mode, stepDepth, depth, breakpoint != null)) {
            mode = BshStepMode.RUN
            val context = BshSuspendContext(line, variables, scriptFile)
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
            breakpoint.fileUrl == scriptFile.url
    }
}
