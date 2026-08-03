package cz.loplex.intellij.bsh.debug

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import java.io.File

/**
 * How a debug session gets its hooks into a running script.
 *
 * Two mechanisms, and they are not equivalent, so the choice is explicit rather than implicit —
 * including the choice to fall back. **Nothing here degrades silently.** Choosing [AGENT] and
 * getting rewriting instead would be indistinguishable, from inside the IDE, from a broken agent:
 * the frames still carry the right line numbers, so the only visible symptom is the absence of
 * things the user may not have thought to look for (one frame instead of the stack, no expandable
 * values, no Evaluate). A session that quietly answers a different question than the one asked is
 * worse than one that refuses to start, so [AGENT] fails loudly and
 * [AGENT_OR_REWRITE] is the opt-in that says "degraded is acceptable".
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
     *
     * If the agent jar cannot be found, this **fails the launch** with the reason.
     */
    AGENT("JVM agent — instrument the interpreter"),

    /**
     * Rewrite the script before launch, prefixing a hook call to every safe statement.
     *
     * Kept because it needs nothing but a source file: no agent to attach, no JVM flag, no
     * bootstrap classloader. Worth choosing deliberately on a JVM that refuses agents.
     */
    REWRITE("Rewrite the script — no agent, no JVM flag"),

    /**
     * Prefer [AGENT]; if its jar cannot be found, rewrite instead and say so in the console.
     *
     * For whoever would rather have a limited session than none. It is a separate mode instead of
     * [AGENT]'s error handling because the two answer different questions — "debug this properly"
     * versus "debug this somehow" — and only the user knows which they meant.
     */
    AGENT_OR_REWRITE("JVM agent, or rewrite if it is unavailable"),
    ;

    /** Whether this mode may use the agent at all. */
    val prefersAgent: Boolean get() = this == AGENT || this == AGENT_OR_REWRITE

    /** Whether rewriting is an acceptable outcome when the agent jar is missing. */
    val toleratesRewriteFallback: Boolean get() = this == AGENT_OR_REWRITE

    companion object {
        /**
         * What a run configuration starts out with, and where an unreadable setting lands.
         *
         * [AGENT] rather than [AGENT_OR_REWRITE]: a default that silently degrades would make the
         * fallback the common case again, which is the thing being fixed here.
         */
        val DEFAULT: BshInstrumentationMode = AGENT

        /**
         * Reads a stored name, tolerating anything it does not recognize.
         *
         * Run-configuration options are persisted as text in the project, so this has to survive a
         * value written by a different version of the plugin — or edited by hand. Falling back to
         * [DEFAULT] costs the user their choice; refusing to launch would cost them the session.
         */
        fun of(name: String?): BshInstrumentationMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Locates the instrumenting agent jar.
 *
 * The jar is shipped as a plugin resource (see the `agentJar` configuration in `build.gradle.kts`)
 * and extracted to a temp file on first use, so it can be passed to a forked
 * JVM as `-javaagent:`. Same mechanism as [BshMavenExt], and for the same reason: the jar has to
 * exist as a file on disk, but its classes must not join the IDE's own classpath.
 *
 * Failure returns `null` rather than throwing, so the caller can decide what that means: under
 * [BshInstrumentationMode.AGENT] it aborts the launch, under
 * [BshInstrumentationMode.AGENT_OR_REWRITE] it rewrites instead and says so.
 */
object BshDebugAgentJar {

    /** Override, mainly for development and for the command-line tools. */
    const val PATH_PROPERTY: String = "bsh.debug.agent.jar"

    /**
     * Restricts the agent to sources whose name starts with one of the prefixes in the named file
     * (`bsh.debug.sources.file`). What an inline script needs: handed a string, BeanShell names the
     * source after the script's own text, so there is no file-name suffix to match on.
     */
    const val SOURCE_PREFIXES_FILE_PROPERTY: String = "bsh.debug.sources.file"

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
