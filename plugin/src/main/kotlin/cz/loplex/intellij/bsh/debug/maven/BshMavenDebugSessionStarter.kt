package cz.loplex.intellij.bsh.debug.maven

import com.intellij.execution.ExecutionListener
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import cz.loplex.intellij.bsh.debug.BshDebugProcess

/**
 * Starts the BeanShell XDebug session for a [BshMavenRunConfiguration] once its Maven process is
 * running. [BshMavenRunConfiguration.getState] has already opened the listening socket and injected
 * the `-D` contract; here we bind the session to the real Maven process handler (so its console and
 * lifecycle track the build) and let the agent connect over the socket, exactly like the `.bsh` path.
 *
 * This runs alongside Maven's own Java (JDWP) debug tab — two tabs, one launch.
 */
class BshMavenDebugSessionStarter : ExecutionListener {

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return
        val pending = BshMavenDebugSupport.consume(env.executionId) ?: return
        val project = env.project

        // The BeanShell session gets its own (fresh) handler; the Maven build's handler is already
        // started and owned by the Java session. Tie our handler's lifecycle to the build ending.
        val bshHandler = BshMavenDebugProcessHandler(pending.server)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                if (!bshHandler.isProcessTerminated) bshHandler.destroyProcess()
            }
        })

        ApplicationManager.getApplication().invokeLater {
            XDebuggerManager.getInstance(project).startSessionAndShowTab(
                "BeanShell (Maven)",
                null,
                object : XDebugProcessStarter() {
                    override fun start(session: XDebugSession): XDebugProcess =
                        // Under the agent the reported lines are snippet-relative and need the
                        // mapper prepared with the pom; rewritten scripts carry pom lines already
                        // and pass none. Either way one session covers every script in the pom.
                        BshDebugProcess(
                            session, bshHandler, pending.server, pending.pomFile,
                            notifyStartOnInit = false,
                            lineMapper = pending.lineMapper,
                            supportsEvaluation = pending.supportsEvaluation,
                            startupNotice = pending.startupNotice,
                        )
                },
            )
        }
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        // Launch aborted before start: release the socket we opened in getState().
        BshMavenDebugSupport.consume(env.executionId)?.let { runCatching { it.server.close() } }
    }
}
