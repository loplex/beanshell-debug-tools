package cz.loplex.intellij.bsh.debug

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * How a debug session gets its hooks into a running script.
 *
 * There are two working mechanisms and they are not equivalent, so the choice is explicit rather
 * than implicit.
 */
enum class BshInstrumentationMode {

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
    AGENT,

    /**
     * Rewrite the script before launch, prefixing a hook call to every safe statement.
     *
     * Kept because it needs nothing but a source file: no agent to attach, no JVM flag, no
     * bootstrap classloader. That makes it the fallback when the agent jar cannot be located or
     * when a host JVM refuses an agent.
     */
    REWRITE,
    ;

    companion object {
        /**
         * The mechanism debug sessions use.
         *
         * A constant for now, intended to become a setting once both paths have seen real use —
         * at which point this reads the configuration instead and the enum stays as it is.
         */
        val CURRENT: BshInstrumentationMode = AGENT
    }
}

/**
 * Locates the instrumenting agent jar.
 *
 * Not yet bundled into the plugin distribution, so the search is deliberately broad and every
 * failure falls back to [BshInstrumentationMode.REWRITE] rather than breaking the session. Once
 * the plugin build ships the jar, the first branch is the only one that stays.
 */
object BshDebugAgentJar {

    /** Override, mainly for development and for the command-line tools. */
    const val PATH_PROPERTY: String = "bsh.debug.agent.jar"

    private const val JAR_PREFIX = "bsh-debug-agent"
    private val log = Logger.getInstance(BshDebugAgentJar::class.java)

    fun locate(): File? {
        System.getProperty(PATH_PROPERTY)?.let { override ->
            val file = File(override)
            if (file.isFile) return file
            log.warn("$PATH_PROPERTY points at a missing file: $override")
        }
        return bundled() ?: builtFromSource()
    }

    /** Where the jar will live once the plugin build bundles it. */
    private fun bundled(): File? {
        val pluginRoot = pluginJarOrClassesDir()?.parentFile ?: return null
        return pluginRoot.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(JAR_PREFIX) && it.name.endsWith(".jar") }
    }

    /**
     * Development fallback: the sibling subproject's build output, reached by walking up from
     * the plugin's own classes. Deliberately tolerant, since it only ever helps a developer
     * running the plugin from source.
     */
    private fun builtFromSource(): File? {
        var directory = pluginJarOrClassesDir()
        repeat(MAX_PARENTS_SEARCHED) {
            directory = directory?.parentFile ?: return null
            val target = File(directory, "agent/instrument/build/libs")
            if (target.isDirectory) {
                return target.listFiles()
                    ?.firstOrNull { it.isFile && it.name.startsWith(JAR_PREFIX) && it.name.endsWith(".jar") }
            }
        }
        return null
    }

    private fun pluginJarOrClassesDir(): File? = runCatching {
        File(BshDebugAgentJar::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull()

    private const val MAX_PARENTS_SEARCHED = 8
}
