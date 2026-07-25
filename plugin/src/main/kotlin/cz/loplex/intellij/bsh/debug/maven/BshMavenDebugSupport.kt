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
import cz.loplex.intellij.bsh.debug.BshDebugInstrumenter
import cz.loplex.intellij.bsh.psi.BshFile
import org.jetbrains.idea.maven.server.MavenServerManager
import java.io.File
import java.net.ServerSocket
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
    )

    /** The listening socket opened in getState(), awaiting the Maven process handler to start a session. */
    class Pending(
        val server: ServerSocket,
        val pomFile: VirtualFile,
    )

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

            val instrumented = BshDebugInstrumenter.instrument(bshFile) { offset ->
                val hostOffset = manager.injectedToHost(bshFile, offset).coerceIn(0, hostDoc.textLength)
                hostDoc.getLineNumber(hostOffset) + 1
            }
            result.add(Prepared(pomVFile, artifactId, tag.name, bshFile.text, instrumented))
        }
        return result
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
