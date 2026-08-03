package cz.loplex.intellij.bsh.debug.maven

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import cz.loplex.intellij.bsh.debug.BshDebugAgentJar
import cz.loplex.intellij.bsh.debug.BshDebugRunner
import cz.loplex.intellij.bsh.debug.BshInstrumentationMode
import cz.loplex.intellij.bsh.debug.BshMavenExt
import cz.loplex.intellij.bsh.debug.agent.BshDebugAgent
import cz.loplex.intellij.bsh.run.BshLaunch
import org.jetbrains.idea.maven.execution.MavenRunConfiguration
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import org.jdom.Element
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

    /**
     * How to instrument the inline script. Persisted by enum **name**, so reordering the enum cannot
     * silently repoint a saved configuration at the other mechanism.
     */
    var instrumentation: BshInstrumentationMode = BshInstrumentationMode.DEFAULT

    override fun getConfigurationEditor(): SettingsEditor<out MavenRunConfiguration> {
        val group = SettingsEditorGroup<BshMavenRunConfiguration>()
        // Maven's own editor stays exactly as it is, as the first tab; ours is added beside it.
        @Suppress("UNCHECKED_CAST")
        group.addEditor("Maven", super.getConfigurationEditor() as SettingsEditor<BshMavenRunConfiguration>)
        group.addEditor("BeanShell", BshMavenSettingsEditor())
        return group
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute(INSTRUMENTATION_ATTRIBUTE, instrumentation.name)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        instrumentation = BshInstrumentationMode.of(element.getAttributeValue(INSTRUMENTATION_ATTRIBUTE))
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        if (passthrough || executor.id != DefaultDebugExecutor.EXECUTOR_ID) {
            return super.getState(executor, environment)
        }
        val clone = clone() as BshMavenRunConfiguration
        clone.passthrough = true
        // Passed rather than read off the clone: whether MavenRunConfiguration.clone() carries a
        // subclass field is its business, and relying on it would break quietly if it changed.
        clone.setUpBeanShellDebug(environment, instrumentation)
        return clone.getState(executor, environment)
    }

    /** Runs on the clone: prepares instrumentation, opens the socket and injects the `-D` contract. */
    private fun setUpBeanShellDebug(environment: ExecutionEnvironment, mode: BshInstrumentationMode) {
        try {
            // Kotlin trusts MavenRunnerParameters' @NotNull annotation on workingDirPath, but that's
            // an upstream guarantee, not one this code controls -- keep the fallback in case it's ever wrong.
            @Suppress("USELESS_ELVIS")
            val workDirPath = runnerParameters.workingDirPath ?: return
            val pomFile = LocalFileSystem.getInstance().findFileByIoFile(File(workDirPath, "pom.xml")) ?: return
            val prepared = ReadAction.compute<List<BshMavenDebugSupport.Prepared>, RuntimeException> {
                BshMavenDebugSupport.prepare(project, pomFile)
            }
            if (prepared.isEmpty()) return  // no inline BeanShell -> proceed as a plain Maven build

            // The agent is preferred here for the same reasons as on the `.bsh` path, and one more
            // specific to Maven: it needs no core extension and no rewritten plugin configuration, so
            // the build Maven runs is byte-for-byte the one in the pom.
            val agentJar = if (mode.prefersAgent) BshDebugAgentJar.locate() else null
            if (mode == BshInstrumentationMode.AGENT && agentJar == null) {
                // Thrown, not downgraded: rewriting would produce a session whose frames look right
                // and which quietly has no call stack, no expandable values and no Evaluate.
                throw ExecutionException(
                    "The BeanShell debug agent jar could not be located, so \"${mode.label}\" cannot run. " +
                        "Set -D${BshDebugAgentJar.PATH_PROPERTY} to point at it, or pick another " +
                        "instrumentation on the BeanShell tab of this run configuration.",
                )
            }

            val server = ServerSocket(0)
            try {
                // Materialise a private runner settings on the clone before injecting our properties.
                val settings = (runnerSettings ?: MavenRunner.getInstance(project).settings).clone()
                runnerSettings = settings
                val props = LinkedHashMap(settings.mavenProperties)
                props[BshDebugAgent.PORT_PROPERTY] = server.localPort.toString()

                val pending = if (agentJar != null) {
                    setUpAgent(settings, props, agentJar, prepared, server)
                } else {
                    val notice = if (mode.toleratesRewriteFallback) BshDebugRunner.REWRITE_FALLBACK_NOTICE else null
                    setUpRewrite(props, prepared, server, notice) ?: return
                }
                settings.mavenProperties = props

                BshMavenDebugSupport.register(environment.executionId, pending)
                LOG.info("Prepared BeanShell Maven debug for ${prepared.size} script(s) on port ${server.localPort}")
            } catch (e: Exception) {
                runCatching { server.close() }
                throw e
            }
        } catch (e: Exception) {
            LOG.warn("Failed to set up BeanShell Maven debug; running without it", e)
        }
    }

    /**
     * Instruments the interpreter: `-javaagent` on the Maven JVM, plus the filter telling the agent
     * which sources to report on.
     *
     * The agent reaches BeanShell wherever it lives — the hook is on the bootstrap classpath, which
     * is what a Maven plugin realm requires — so the pom is left completely alone. The filter is not
     * optional: instrumenting the interpreter also reaches BeanShell's own commands, which are `.bsh`
     * files inside the jar, so without it the session would stop inside `print.bsh`.
     */
    private fun setUpAgent(
        settings: MavenRunnerSettings,
        props: MutableMap<String, String>,
        agentJar: File,
        prepared: List<BshMavenDebugSupport.Prepared>,
        server: ServerSocket,
    ): BshMavenDebugSupport.Pending {
        val prefixes = BshMavenDebugSupport.writeSourcePrefixes(prepared)
        @Suppress("UsePropertyAccessSyntax")
        settings.setVmOptions(
            listOfNotNull(
                settings.vmOptions.takeIf { it.isNotBlank() },
                "-javaagent:${agentJar.absolutePath}",
            ).joinToString(" "),
        )
        props[BshDebugAgentJar.SOURCE_PREFIXES_FILE_PROPERTY] = prefixes.absolutePath
        return BshMavenDebugSupport.Pending(
            server,
            prepared.first().pomFile,
            // The agent reports snippet-relative lines under a name derived from the script text.
            lineMapper = BshMavenDebugSupport.lineMapper(prepared),
            supportsEvaluation = true,
        )
    }

    /**
     * The fallback: hand Maven a core extension that swaps the inline script for an instrumented
     * copy. Returns null when even the rewriting hook cannot be located, which leaves the build
     * running as an ordinary one.
     */
    private fun setUpRewrite(
        props: MutableMap<String, String>,
        prepared: List<BshMavenDebugSupport.Prepared>,
        server: ServerSocket,
        startupNotice: String?,
    ): BshMavenDebugSupport.Pending? {
        val callbackJar = BshLaunch.debugAgentClasspath() ?: run {
            LOG.warn("BeanShell debug hook not found on the plugin classpath; running without BeanShell debug")
            return null
        }
        // One manifest line per script: artifactId, tag, original text file, instrumented text file.
        // The script bodies go to their own temp files so multi-line scripts survive intact.
        val manifest = prepared.joinToString("\n") { p ->
            val originalTemp = FileUtil.createTempFile("bsh-maven-orig", ".bsh", true)
            originalTemp.writeText(p.original, StandardCharsets.UTF_8)
            val instrumentedTemp = FileUtil.createTempFile("bsh-maven-instr", ".bsh", true)
            instrumentedTemp.writeText(p.instrumented, StandardCharsets.UTF_8)
            listOf(p.artifactId, p.tag, originalTemp.absolutePath, instrumentedTemp.absolutePath).joinToString("\t")
        }
        val manifestTemp = FileUtil.createTempFile("bsh-maven-manifest", ".txt", true)
        manifestTemp.writeText(manifest, StandardCharsets.UTF_8)

        props[BshMavenDebugSupport.EXT_CLASS_PATH_PROPERTY] =
            BshMavenDebugSupport.mergedExtClassPath(BshMavenExt.extensionJarPath())
        props[BshMavenDebugSupport.MANIFEST_PROPERTY] = manifestTemp.absolutePath
        props[BshMavenDebugSupport.CALLBACK_JAR_PROPERTY] = callbackJar
        // The rewritten script carries pom.xml lines in its own hook calls, so no mapper is needed;
        // and a rewritten script hands the hook a NameSpace, which cannot evaluate.
        return BshMavenDebugSupport.Pending(server, prepared.first().pomFile, startupNotice = startupNotice)
    }

    private companion object {
        private val LOG = logger<BshMavenRunConfiguration>()

        /** Attribute name in the saved run configuration. */
        private const val INSTRUMENTATION_ATTRIBUTE = "bshInstrumentation"
    }
}
