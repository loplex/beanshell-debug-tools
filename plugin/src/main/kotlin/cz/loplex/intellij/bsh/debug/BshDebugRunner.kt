package cz.loplex.intellij.bsh.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.util.execution.ParametersListUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import cz.loplex.intellij.bsh.debug.agent.BshDebugAgent
import cz.loplex.intellij.bsh.psi.BshFile
import cz.loplex.intellij.bsh.run.BshLaunch
import cz.loplex.intellij.bsh.run.BshRunConfiguration
import java.io.File
import java.net.ServerSocket

/**
 * Runs a [BshRunConfiguration] under the Debug executor: instruments the script,
 * opens a debug server socket, and launches the interpreter with the debug agent
 * on the classpath and the server port passed through `bsh.debug.port`.
 */
class BshDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "BshDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is BshRunConfiguration

    @Throws(ExecutionException::class)
    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor {
        val configuration = environment.runProfile as BshRunConfiguration
        val project = environment.project

        val scriptFile = LocalFileSystem.getInstance().findFileByPath(configuration.scriptPath)
            ?: throw ExecutionException("BeanShell script not found: ${configuration.scriptPath}")
        val psiFile = ReadAction.compute<BshFile?, RuntimeException> {
            PsiManager.getInstance(project).findFile(scriptFile) as? BshFile
        } ?: throw ExecutionException("Not a BeanShell file: ${configuration.scriptPath}")

        // AGENT mode instruments the interpreter and runs the script untouched; REWRITE mode
        // prefixes hook calls into a temp copy. Falling back keeps a session working when the
        // agent jar is missing, which matters while it is not yet bundled with the plugin.
        val agentJar = if (BshInstrumentationMode.CURRENT == BshInstrumentationMode.AGENT) {
            BshDebugAgentJar.locate()
        } else {
            null
        }
        val useAgent = agentJar != null

        val scriptToRun: File
        val classpath: String
        if (useAgent) {
            scriptToRun = File(configuration.scriptPath)
            classpath = BshLaunch.classpath(configuration)
        } else {
            val instrumented = ReadAction.compute<String, RuntimeException> {
                BshDebugInstrumenter.instrument(psiFile)
            }
            val instrumentedFile = FileUtil.createTempFile("bsh-debug", ".bsh", true)
            instrumentedFile.writeText(instrumented)
            scriptToRun = instrumentedFile

            val rewriteAgent = BshLaunch.debugAgentClasspath()
                ?: throw ExecutionException("BeanShell debug agent could not be located on the plugin classpath")
            classpath = listOf(rewriteAgent, BshLaunch.classpath(configuration)).joinToString(File.pathSeparator)
        }

        // When Java debugging is available, run the JVM under JDWP so breakpoints in the
        // Java code called from the script are honoured by a second (Java) debug session.
        val javaDebug = BshJavaDebugAttach.isAvailable()
        val jdwpPort = if (javaDebug) freePort() else -1

        val server = ServerSocket(0)
        val commandLine = GeneralCommandLine()
            .withExePath(BshLaunch.javaExecutable(configuration, project).absolutePath)
        if (javaDebug) {
            // suspend=y: the JVM waits until the Java debugger attaches, so breakpoints in
            // the Java code are armed before any script code runs (no attach race).
            commandLine.withParameters(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:$jdwpPort",
            )
        }
        if (useAgent) {
            commandLine.withParameters("-javaagent:${agentJar!!.absolutePath}")
            // Instrumenting the interpreter reaches strictly more code than rewriting one script
            // does, so without this the session would also stop inside BeanShell's own commands --
            // print and friends are .bsh files on the classpath.
            commandLine.withParameters("-Dbsh.debug.sources=${scriptToRun.name}")
        }
        commandLine
            .withParameters("-D${BshDebugAgent.PORT_PROPERTY}=${server.localPort}")
            .withParameters("-cp", classpath)
            .withParameters(BshLaunch.MAIN_CLASS)
            .withParameters(scriptToRun.absolutePath)
            .withParameters(ParametersListUtil.parse(configuration.programArguments))
            .withWorkDirectory(BshLaunch.workingDirectory(configuration, File(configuration.scriptPath)))
            .withCharset(Charsets.UTF_8)

        val processHandler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(processHandler)

        val session = XDebuggerManager.getInstance(project).startSession(
            environment,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess =
                    BshDebugProcess(
                        session, processHandler, server, scriptFile,
                        // Identity line mapping for a standalone .bsh, so the agent can be
                        // given the breakpoint set and filter locally.
                        pushFilterToAgent = useAgent,
                    )
            },
        )

        if (javaDebug) {
            runCatching { BshJavaDebugAttach.attach(project, "127.0.0.1", jdwpPort) }
        }
        return session.runContentDescriptor
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
