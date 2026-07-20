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

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        val scriptFile = File(configuration.scriptPath)
        if (!scriptFile.isFile) {
            throw ExecutionException("BeanShell script not found: ${configuration.scriptPath}")
        }

        val javaExe = resolveJavaExecutable()

        val commandLine = GeneralCommandLine()
            .withExePath(javaExe.absolutePath)
            .withParameters("-cp", configuration.interpreterClasspath)
            .withParameters(MAIN_CLASS)
            .withParameters(scriptFile.absolutePath)
            .withParameters(ParametersListUtil.parse(configuration.programArguments))
            .withWorkDirectory(resolveWorkingDirectory(scriptFile))
            .withCharset(Charsets.UTF_8)

        val handler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    private fun resolveJavaExecutable(): File {
        val home = configuration.jrePath.ifBlank { System.getProperty("java.home") }
        val bin = File(home, "bin")
        val windows = File(bin, "java.exe")
        val exe = if (windows.isFile) windows else File(bin, "java")
        if (!exe.isFile) {
            throw ExecutionException("Java executable not found under: $home")
        }
        return exe
    }

    private fun resolveWorkingDirectory(scriptFile: File): File {
        val configured = configuration.workingDirectory
        if (configured.isNotBlank()) {
            return File(configured)
        }
        return scriptFile.parentFile ?: File(".")
    }

    companion object {
        private const val MAIN_CLASS = "bsh.Interpreter"
    }
}
