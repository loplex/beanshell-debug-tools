package cz.loplex.intellij.bsh.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.configurations.CommandLineState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.roots.ProjectRootManager
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

        val javaExe = resolveJavaExecutable()
        val classpath = resolveClasspath()

        val commandLine = GeneralCommandLine()
            .withExePath(javaExe.absolutePath)
            .withParameters("-cp", classpath)
            .withParameters(MAIN_CLASS)
            .withParameters(scriptFile.absolutePath)
            .withParameters(ParametersListUtil.parse(configuration.programArguments))
            .withWorkDirectory(resolveWorkingDirectory(scriptFile))
            .withCharset(Charsets.UTF_8)

        val handler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    private fun resolveClasspath(): String {
        val configured = configuration.interpreterClasspath
        if (configured.isNotBlank()) return configured
        return bundledBshClasspath()
            ?: throw ExecutionException(
                "No BeanShell classpath configured and the bundled interpreter could not be located. " +
                    "Set the BeanShell classpath in the run configuration."
            )
    }

    /** Locates the BeanShell jar bundled with the plugin, if present. */
    private fun bundledBshClasspath(): String? = try {
        val interpreter = Class.forName("bsh.Interpreter")
        PathManager.getJarPathForClass(interpreter)
    } catch (_: Throwable) {
        null
    }

    private fun resolveJavaExecutable(): File {
        val home = configuration.jrePath.ifBlank { projectJdkHome() ?: System.getProperty("java.home") }
        val bin = File(home, "bin")
        val windows = File(bin, "java.exe")
        val exe = if (windows.isFile) windows else File(bin, "java")
        if (!exe.isFile) {
            throw ExecutionException("Java executable not found under: $home")
        }
        return exe
    }

    /** Home of the JDK configured for the project, when it provides a usable `java` launcher. */
    private fun projectJdkHome(): String? {
        val home = ProjectRootManager.getInstance(project).projectSdk?.homePath ?: return null
        val bin = File(home, "bin")
        val hasJava = File(bin, "java").isFile || File(bin, "java.exe").isFile
        return if (hasJava) home else null
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
