package cz.loplex.intellij.bsh.debug.maven

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.openapi.util.io.FileUtil
import cz.loplex.intellij.bsh.debug.BshDebugInstrumenter
import cz.loplex.intellij.bsh.psi.BshFile
import org.jetbrains.idea.maven.server.MavenServerManager
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared plumbing for debugging inline BeanShell scripts run by Maven: it discovers the injected
 * script in a `pom.xml`, instruments it and computes the snippet↔pom line map, exposes the Maven
 * property contract handed to the bundled core extension, and coordinates the hand-off from
 * [BshMavenRunConfiguration.getState] (which opens the socket) to [BshMavenDebugSessionStarter]
 * (which starts the XDebug session once the Maven process is running).
 */
object BshMavenDebugSupport {

    /** Maven properties (`-D…`) understood by the bundled core extension / debug agent. */
    const val EXT_CLASS_PATH_PROPERTY = "maven.ext.class.path"

    /** Path to the manifest listing every script to rewrite (`artifactId\ttag\toriginalFile\tinstrumentedFile`). */
    const val MANIFEST_PROPERTY = "bsh.debug.manifest"
    const val CALLBACK_JAR_PROPERTY = "bsh.debug.callback.jar"

    /** What `Interpreter.eval(String)` prefixes to the synthetic name it gives a script. */
    private const val NAME_LEAD = "inline evaluation of: ``"

    /**
     * How much of the script goes into the prefix used to recognize it.
     *
     * Below BeanShell's own 80-character cut so the elision can never fall inside the prefix, and
     * well above the length at which two `<script>` blocks in one pom would still look alike.
     */
    private const val NAME_PREFIX_CHARS = 60

    /**
     * One inline BeanShell script the extension must rewrite, computed from the pom.xml under read
     * access. Its [instrumented] text already carries **absolute pom.xml line numbers** (baked at
     * instrumentation time), so the agent reports pom lines directly and no line map is needed.
     */
    class Prepared(
        val pomFile: VirtualFile,
        /** Plugin artifactId owning the script (locates the plugin whose config node is rewritten). */
        val artifactId: String,
        /** The configuration element name holding the script (`script`/`condition`/`source`). */
        val tag: String,
        /** The script as injected (used by the extension to match the right node by content). */
        val original: String,
        /** Instrumented script with pom.xml host lines baked into every `step(...)` call. */
        val instrumented: String,
        /**
         * How this snippet looks to the instrumenting agent: the synthetic source names BeanShell
         * may give it, each with the snippet-line → pom-line map that belongs to it.
         *
         * More than one because whether the calling plugin trimmed the XML text before handing it
         * over changes both halves — the name gains or loses its leading blanks, and every line
         * shifts by however many the trim swallowed. Rather than guess which plugin does what, both
         * readings are offered and the one the agent's reported name matches wins.
         */
        val agentSources: List<AgentSource>,
    )

    /**
     * One possible reading of an inline snippet as the agent will see it.
     *
     * [namePrefix] is what BeanShell's synthetic name starts with. Deliberately a *prefix*: the full
     * name flattens newlines to spaces, is cut at 80 characters with `" . . . "` appended, and gains
     * a `;` if the script did not end in one — all of which a short prefix survives.
     */
    class AgentSource(
        val namePrefix: String,
        /** 1-based snippet line → 1-based pom.xml line. */
        val lineMap: Map<Int, Int>,
    )

    /** The listening socket opened in getState(), awaiting the Maven process handler to start a session. */
    class Pending(
        val server: ServerSocket,
        val pomFile: VirtualFile,
        /**
         * Translates an agent-reported position into a pom.xml line, or null when the scripts were
         * rewritten instead — those already report pom lines, so there is nothing to translate.
         */
        val lineMapper: ((String, Int) -> Int)? = null,
        /** Whether the session may offer Watches and Set Value; only the agent can evaluate. */
        val supportsEvaluation: Boolean = false,
        /** Printed to the console once the session starts; set when the rewriting fallback fired. */
        val startupNotice: String? = null,
    )

    /**
     * A line mapper over [prepared]: picks the snippet whose name the agent reported, then looks the
     * line up in that snippet's map.
     *
     * The longest matching prefix wins, so two snippets sharing an opening line still resolve to the
     * right one. An unrecognized name — BeanShell's own commands, a script the pom does not contain —
     * maps to -1, which is how the session says "not in this file".
     */
    fun lineMapper(prepared: List<Prepared>): (String, Int) -> Int {
        val sources = prepared.flatMap { it.agentSources }.sortedByDescending { it.namePrefix.length }
        return { reported, line ->
            sources.firstOrNull { reported.startsWith(it.namePrefix) }?.lineMap?.get(line) ?: -1
        }
    }

    /** Writes the agent's source-prefix filter file, one prefix per line. */
    fun writeSourcePrefixes(prepared: List<Prepared>): File {
        val file = FileUtil.createTempFile("bsh-maven-sources", ".txt", true)
        file.writeText(
            prepared.flatMap { it.agentSources }.map { it.namePrefix }.distinct().joinToString("\n"),
            StandardCharsets.UTF_8,
        )
        return file
    }

    private val pending = ConcurrentHashMap<Long, Pending>()

    fun register(executionId: Long, value: Pending) {
        pending[executionId] = value
    }

    fun consume(executionId: Long): Pending? = pending.remove(executionId)

    /**
     * Finds every inline BeanShell script injected into [pomVFile] and instruments each with its
     * pom.xml host lines baked in. Returns an empty list when the pom has no inline BeanShell (the
     * run then proceeds as a plain Maven build). Must be called under a read action.
     */
    fun prepare(project: Project, pomVFile: VirtualFile): List<Prepared> {
        val psi = PsiManager.getInstance(project).findFile(pomVFile) as? XmlFile ?: return emptyList()
        val manager = InjectedLanguageManager.getInstance(project)
        val hostDoc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return emptyList()

        val result = ArrayList<Prepared>()
        for (xmlText in PsiTreeUtil.collectElementsOfType(psi, XmlText::class.java)) {
            val host = xmlText as? PsiLanguageInjectionHost ?: continue
            val bshFile = manager.getInjectedPsiFiles(host)?.firstNotNullOfOrNull { it.first as? BshFile } ?: continue
            val tag = xmlText.parentTag ?: continue
            val artifactId = enclosingPlugin(tag)?.findFirstSubTag("artifactId")?.value?.trimmedText ?: continue

            val pomLineOf = { offset: Int ->
                val hostOffset = manager.injectedToHost(bshFile, offset).coerceIn(0, hostDoc.textLength)
                hostDoc.getLineNumber(hostOffset) + 1
            }
            val instrumented = BshDebugInstrumenter.instrument(bshFile, pomLineOf)
            result.add(
                Prepared(
                    pomVFile, artifactId, tag.name, bshFile.text, instrumented,
                    agentSources = agentSources(bshFile.text, pomLineOf),
                ),
            )
        }
        return result
    }

    /**
     * The name BeanShell gives a script it was handed as a string, reproduced exactly.
     *
     * Mirrors `Interpreter.eval(String, NameSpace)` in BeanShell 2.0b6: a `;` is appended if the
     * script lacks one, newlines become spaces, and anything past 80 characters is replaced by
     * `" . . . "`. Only the tests need the whole name — production code matches on a prefix, which is
     * what makes it independent of the elision and of the appended `;` — but having the full rule in
     * one readable place is what pins the prefix down as correct.
     */
    fun beanShellSourceName(script: String): String {
        val terminated = if (script.endsWith(";")) script else "$script;"
        val flat = terminated.replace('\n', ' ').replace('\r', ' ')
        val shown = if (flat.length > 80) flat.substring(0, 80) + " . . . " else flat
        return "$NAME_LEAD$shown''"
    }

    /**
     * The readings of [text] the agent may report, one per trimming the calling plugin might apply.
     *
     * Only distinct offsets are worth offering, so an already-trimmed script yields a single entry.
     */
    private fun agentSources(text: String, pomLineOf: (Int) -> Int): List<AgentSource> {
        val trimStart = text.indexOfFirst { !it.isWhitespace() }
        if (trimStart < 0) return emptyList()  // whitespace only: nothing to run, nothing to map
        // Each reading pairs the text the plugin would hand over with where it starts in the
        // injected text -- the name comes from the former, the line numbering from the latter.
        return listOf(trimStart to text.trim(), 0 to text)
            .distinctBy { (_, body) -> body }
            .map { (start, body) -> AgentSource(namePrefix(body), lineMap(text, start, pomLineOf)) }
    }

    /**
     * How BeanShell will name [body], truncated to a prefix.
     *
     * Mirrors the front of [beanShellSourceName]: newlines become spaces. Only the first
     * [NAME_PREFIX_CHARS] characters are kept, which puts the prefix clear of BeanShell's own
     * 80-character elision and of the `;` it may append, while staying specific enough to tell two
     * snippets in one pom apart.
     */
    private fun namePrefix(body: String): String {
        val flat = body.replace('\n', ' ').replace('\r', ' ')
        return NAME_LEAD + flat.substring(0, minOf(NAME_PREFIX_CHARS, flat.length))
    }

    /**
     * Snippet line → pom.xml line for a snippet starting at [start] in the injected text.
     *
     * Built by walking the snippet's own line starts, because that is the coordinate system the
     * agent reports in: BeanShell parses the text it was handed and counts from its first line.
     */
    private fun lineMap(text: String, start: Int, pomLineOf: (Int) -> Int): Map<Int, Int> {
        val map = LinkedHashMap<Int, Int>()
        var snippetLine = 1
        var offset = start
        while (offset <= text.length) {
            map[snippetLine] = pomLineOf(offset)
            val newline = text.indexOf('\n', offset)
            if (newline < 0) break
            offset = newline + 1
            snippetLine++
        }
        return map
    }

    /**
     * Our extension jar joined to Maven's own `maven.ext.class.path` value (its build-output event
     * listener). Maven emits its own `-Dmaven.ext.class.path` first, so ours — appended as a Maven
     * property, hence rendered last — wins; including the event listener keeps the Maven console working.
     */
    fun mergedExtClassPath(extJar: String): String {
        val eventListener: File? = runCatching { MavenServerManager.getInstance().getMavenEventListener() }.getOrNull()
        return if (eventListener != null) eventListener.absolutePath + File.pathSeparator + extJar else extJar
    }

    private fun enclosingPlugin(tag: XmlTag): XmlTag? {
        var current: XmlTag? = tag
        while (current != null) {
            if (current.name == "plugin") return current
            current = current.parentTag
        }
        return null
    }
}
