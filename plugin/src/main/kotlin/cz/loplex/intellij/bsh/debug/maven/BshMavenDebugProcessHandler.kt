package cz.loplex.intellij.bsh.debug.maven

import com.intellij.execution.process.ProcessHandler
import java.io.OutputStream
import java.net.ServerSocket

/**
 * A processless [ProcessHandler] backing the "BeanShell (Maven)" debug tab. The debugging happens
 * over [server] inside `BshDebugProcess`; this handler only represents that session's lifecycle.
 *
 * It must be a *fresh* handler (not the Maven build's own, already-started one) — reusing the
 * Maven handler makes the second XDebug session call `startNotify` on an already-started process.
 * [BshMavenDebugSessionStarter] terminates this handler when the Maven build process ends.
 */
class BshMavenDebugProcessHandler(private val server: ServerSocket) : ProcessHandler() {

    override fun destroyProcessImpl() = closeAndNotify()

    override fun detachProcessImpl() = closeAndNotify()

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    private fun closeAndNotify() {
        runCatching { server.close() }
        notifyProcessTerminated(0)
    }
}
