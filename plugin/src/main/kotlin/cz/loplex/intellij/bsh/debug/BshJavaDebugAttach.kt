package cz.loplex.intellij.bsh.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Optionally attaches IntelliJ's Java (JDWP) debugger to the forked BeanShell JVM
 * so that breakpoints in the Java code invoked from a script are honored.
 *
 * Implemented purely through platform API plus the "Remote" run-configuration
 * type (id `Remote`) contributed by the Java plugin, looked up by id — so this
 * class carries no compile-time or class-loading dependency on the Java plugin.
 * When the Java plugin is absent it simply reports unavailable and BeanShell
 * debugging continues on its own.
 */
object BshJavaDebugAttach {

    private const val REMOTE_TYPE_ID = "Remote"
    private val LOG = logger<BshJavaDebugAttach>()

    fun isAvailable(): Boolean =
        runCatching { ConfigurationTypeUtil.findConfigurationType(REMOTE_TYPE_ID) != null }.getOrDefault(false)

    /** Attaches the Java debugger to `host:port` (a JDWP socket the JVM is listening on). */
    fun attach(project: Project, host: String, port: Int) {
        val type = ConfigurationTypeUtil.findConfigurationType(REMOTE_TYPE_ID) ?: return
        val factory = type.configurationFactories.firstOrNull() ?: return
        val settings = RunManager.getInstance(project).createConfiguration("BeanShell (JVM attach)", factory)

        val configuration = settings.configuration
        setField(configuration, "HOST", host)
        setField(configuration, "PORT", port.toString())
        setBoolean(configuration, "USE_SOCKET_TRANSPORT", true)
        setBoolean(configuration, "SERVER_MODE", false) // the IDE connects; the JVM listens

        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
    }

    private fun setField(target: Any, name: String, value: String) {
        runCatching { target.javaClass.getField(name).set(target, value) }
            .onFailure { LOG.info("BeanShell debug: cannot set $name on remote configuration", it) }
    }

    private fun setBoolean(target: Any, name: String, value: Boolean) {
        runCatching { target.javaClass.getField(name).setBoolean(target, value) }
            .onFailure { LOG.info("BeanShell debug: cannot set $name on remote configuration", it) }
    }
}
