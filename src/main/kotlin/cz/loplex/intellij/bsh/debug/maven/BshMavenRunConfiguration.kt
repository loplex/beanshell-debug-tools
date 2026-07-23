package cz.loplex.intellij.bsh.debug.maven

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import cz.loplex.intellij.bsh.debug.BshMavenExt
import cz.loplex.intellij.bsh.debug.agent.BshDebugAgent
import cz.loplex.intellij.bsh.run.BshLaunch
import org.jetbrains.idea.maven.execution.MavenRunConfiguration
import org.jetbrains.idea.maven.execution.MavenRunner
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

/**
 * A Maven run configuration that additionally debugs the inline BeanShell `<script>` blocks
 * executed by the build. It inherits every Maven option (goals, profiles, JRE, VM options and the
 * settings UI) from [MavenRunConfiguration]; only [getState] is augmented, and only for Debug.
 *
 * When debugging, it instruments the inline script, opens a listening socket and injects the
 * extension + agent contract as Maven properties, then lets [MavenRunConfiguration] build the run
 * normally — so the Java (JDWP) debug tab still appears for free, and the BeanShell session is
 * started alongside it by [BshMavenDebugSessionStarter] once the Maven process is running.
 *
 * The augmentation mutates a **clone** of this configuration (deep-copied settings), never the
 * saved one, so the internal `-D` properties (temp paths, socket port) are never persisted.
 */
class BshMavenRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : MavenRunConfiguration(project, factory, name) {

    /** Set on the throwaway clone so its own getState() call skips re-augmentation. */
    @Transient
    private var passthrough = false

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        if (passthrough || executor.id != DefaultDebugExecutor.EXECUTOR_ID) {
            return super.getState(executor, environment)
        }
        val clone = clone() as BshMavenRunConfiguration
        clone.passthrough = true
        clone.setUpBeanShellDebug(environment)
        return clone.getState(executor, environment)
    }

    /** Runs on the clone: prepares instrumentation, opens the socket and injects the `-D` contract. */
    private fun setUpBeanShellDebug(environment: ExecutionEnvironment) {
        try {
            val workDirPath = runnerParameters?.workingDirPath ?: return
            val pomFile = LocalFileSystem.getInstance().findFileByIoFile(File(workDirPath, "pom.xml")) ?: return
            val prepared = ReadAction.compute<BshMavenDebugSupport.Prepared?, RuntimeException> {
                BshMavenDebugSupport.prepare(project, pomFile)
            } ?: return  // no inline BeanShell -> proceed as a plain Maven build

            val callbackJar = BshLaunch.debugAgentClasspath() ?: run {
                LOG.warn("BeanShell debug agent not found on the plugin classpath; running without BeanShell debug")
                return
            }

            val server = ServerSocket(0)
            try {
                val scriptTemp = FileUtil.createTempFile("bsh-maven-debug", ".bsh", true)
                scriptTemp.writeText(prepared.instrumented, StandardCharsets.UTF_8)

                // Materialise a private runner settings on the clone before injecting our properties.
                val settings = (runnerSettings ?: MavenRunner.getInstance(project).settings).clone()
                setRunnerSettings(settings)
                val props = LinkedHashMap(settings.mavenProperties)
                props[BshMavenDebugSupport.EXT_CLASS_PATH_PROPERTY] =
                    BshMavenDebugSupport.mergedExtClassPath(BshMavenExt.extensionJarPath())
                props[BshDebugAgent.PORT_PROPERTY] = server.localPort.toString()
                props[BshMavenDebugSupport.TARGET_PROPERTY] = prepared.target
                props[BshMavenDebugSupport.SCRIPT_FILE_PROPERTY] = scriptTemp.absolutePath
                props[BshMavenDebugSupport.CALLBACK_JAR_PROPERTY] = callbackJar
                settings.setMavenProperties(props)

                BshMavenDebugSupport.register(
                    environment.executionId,
                    BshMavenDebugSupport.Pending(server, prepared.pomFile, prepared.lineMap),
                )
                LOG.info("Prepared BeanShell Maven debug for ${prepared.target} on port ${server.localPort}")
            } catch (e: Exception) {
                runCatching { server.close() }
                throw e
            }
        } catch (e: Exception) {
            LOG.warn("Failed to set up BeanShell Maven debug; running without it", e)
        }
    }

    private companion object {
        private val LOG = logger<BshMavenRunConfiguration>()
    }
}
