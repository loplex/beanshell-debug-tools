package cz.loplex.intellij.bsh.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.configurations.CommandLineState
import com.intellij.util.execution.ParametersListUtil
import java.io.File

/**
 * Builds and launches the external JVM process that runs a BeanShell script:
 *
 * `<java> -cp <interpreterClasspath> bsh.Interpreter <script> [args...]`
 */
class BshCommandLineState(
    environment: ExecutionEnvironment,
    private val configuration: BshRunConfiguration,
) : CommandLineState(environment) {

    private val project = environment.project

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        val scriptFile = File(configuration.scriptPath)
        if (!scriptFile.isFile) {
            throw ExecutionException("BeanShell script not found: ${configuration.scriptPath}")
        }

        val commandLine = GeneralCommandLine()
            .withExePath(BshLaunch.javaExecutable(configuration, project).absolutePath)
            .withParameters("-cp", BshLaunch.classpath(configuration))
            .withParameters(BshLaunch.MAIN_CLASS)
            .withParameters(scriptFile.absolutePath)
            .withParameters(ParametersListUtil.parse(configuration.programArguments))
            .withWorkDirectory(BshLaunch.workingDirectory(configuration, scriptFile))
            .withCharset(Charsets.UTF_8)

        val handler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}
