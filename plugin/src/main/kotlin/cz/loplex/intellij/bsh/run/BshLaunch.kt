package cz.loplex.intellij.bsh.run

import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File

/** Shared launch helpers used by both the run and debug executors. */
object BshLaunch {

    const val MAIN_CLASS = "bsh.Interpreter"

    @Throws(ExecutionException::class)
    fun javaExecutable(configuration: BshRunConfiguration, project: Project): File {
        val home = configuration.jrePath.ifBlank { projectJdkHome(project) ?: System.getProperty("java.home") }
        val bin = File(home, "bin")
        val windows = File(bin, "java.exe")
        val exe = if (windows.isFile) windows else File(bin, "java")
        if (!exe.isFile) throw ExecutionException("Java executable not found under: $home")
        return exe
    }

    @Throws(ExecutionException::class)
    fun classpath(configuration: BshRunConfiguration): String {
        val configured = configuration.interpreterClasspath
        if (configured.isNotBlank()) return configured
        return bundledBshClasspath()
            ?: throw ExecutionException(
                "No BeanShell classpath configured and the bundled interpreter could not be located. " +
                    "Set the BeanShell classpath in the run configuration."
            )
    }

    fun workingDirectory(configuration: BshRunConfiguration, scriptFile: File): File {
        val configured = configuration.workingDirectory
        return if (configured.isNotBlank()) File(configured) else scriptFile.parentFile ?: File(".")
    }

    /** Classpath entry (jar or output dir) that provides the debug agent. */
    fun debugAgentClasspath(): String? = try {
        PathManager.getJarPathForClass(Class.forName("cz.loplex.intellij.bsh.debug.agent.BshDebugAgent"))
    } catch (_: Throwable) {
        null
    }

    private fun bundledBshClasspath(): String? = try {
        PathManager.getJarPathForClass(Class.forName("bsh.Interpreter"))
    } catch (_: Throwable) {
        null
    }

    private fun projectJdkHome(project: Project): String? {
        val home = ProjectRootManager.getInstance(project).projectSdk?.homePath ?: return null
        val bin = File(home, "bin")
        val hasJava = File(bin, "java").isFile || File(bin, "java.exe").isFile
        return if (hasJava) home else null
    }
}
