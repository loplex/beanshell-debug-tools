package cz.loplex.intellij.bsh.debug

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import java.io.File

/**
 * How a debug session gets its hooks into a running script.
 *
 * There are two working mechanisms and they are not equivalent, so the choice is explicit rather
 * than implicit.
 */
enum class BshInstrumentationMode(
    /**
     * What the run configuration's combo box shows.
     *
     * Kept short on purpose: a `ComboBox` is laid out to fit its widest item, so a sentence here
     * widens the whole settings panel. The trade-offs go in the comment under the field, which is
     * broken into lines by hand — see `BshSettingsEditor`.
     */
    val label: String,
) {

    /**
     * Instrument the BeanShell interpreter with a `-javaagent` and leave the script untouched.
     *
     * Preferred, for three reasons the rewriting approach cannot match:
     *
     *  * **The script stays the script.** A rewritten script carries the injected call in its own
     *    text, so BeanShell reports it back through `NameSpace.getInvocationText()` — which means
     *    it shows up in error messages and stack traces. The user then debugs a program that is
     *    visibly not the one they wrote.
     *  * **Reach.** Rewriting needs the source before launch. That misses `eval(String)` input,
     *    scripts loaded as classpath resources (including BeanShell's own commands, such as
     *    `print`), and script text a library builds at runtime — exactly the shapes that appear
     *    when a third-party library embeds BeanShell.
     *  * **Brace-less bodies.** `if (x) foo();` cannot be rewritten without detaching the body,
     *    but the agent reports the body node without moving any text.
     */
    AGENT("JVM agent — instrument the interpreter"),

    /**
     * Rewrite the script before launch, prefixing a hook call to every safe statement.
     *
     * Kept because it needs nothing but a source file: no agent to attach, no JVM flag, no
     * bootstrap classloader. That makes it the fallback when the agent jar cannot be located or
     * when a host JVM refuses an agent.
     */
    REWRITE("Rewrite the script — no agent, no JVM flag"),
    ;

    companion object {
        /** What a run configuration starts out with, and where an unreadable setting lands. */
        val DEFAULT: BshInstrumentationMode = AGENT

        /**
         * Reads a stored name, tolerating anything it does not recognise.
         *
         * Run-configuration options are persisted as text in the project, so this has to survive a
         * value written by a different version of the plugin — or edited by hand. Falling back to
         * [DEFAULT] costs the user their choice; refusing to launch would cost them the session.
         */
        fun of(name: String?): BshInstrumentationMode = values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Locates the instrumenting agent jar.
 *
 * The jar is shipped as a plugin resource (see the `agentJar` configuration in
 * `build.gradle.kts`) and extracted to a temp file on first use, so it can be passed to a forked
 * JVM as `-javaagent:`. Same mechanism as [BshMavenExt], and for the same reason: the jar has to
 * exist as a file on disk, but its classes must not join the IDE's own classpath.
 *
 * Failure returns `null` rather than throwing — the caller falls back to
 * [BshInstrumentationMode.REWRITE], which is degraded but working.
 */
object BshDebugAgentJar {

    /** Override, mainly for development and for the command-line tools. */
    const val PATH_PROPERTY: String = "bsh.debug.agent.jar"

    private const val RESOURCE = "/beanshell/bsh-debug-agent.jar"
    private val log = Logger.getInstance(BshDebugAgentJar::class.java)

    @Volatile
    private var cached: File? = null

    fun locate(): File? {
        System.getProperty(PATH_PROPERTY)?.let { override ->
            val file = File(override)
            if (file.isFile) return file
            log.warn("$PATH_PROPERTY points at a missing file: $override")
        }
        return bundled()
    }

    private fun bundled(): File? {
        cached?.let { if (it.isFile) return it }
        synchronized(this) {
            cached?.let { if (it.isFile) return it }
            val stream = javaClass.getResourceAsStream(RESOURCE)
            if (stream == null) {
                log.warn("bundled agent jar not found on the plugin classpath: $RESOURCE")
                return null
            }
            return runCatching {
                val target = FileUtil.createTempFile("bsh-debug-agent", ".jar", true)
                stream.use { input -> target.outputStream().use { input.copyTo(it) } }
                target.also { cached = it }
            }.onFailure { log.warn("cannot extract the bundled agent jar", it) }.getOrNull()
        }
    }
}
