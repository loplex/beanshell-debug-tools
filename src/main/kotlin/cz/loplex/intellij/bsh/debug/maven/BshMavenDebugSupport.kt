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
    const val TARGET_PROPERTY = "bsh.debug.target"
    const val SCRIPT_FILE_PROPERTY = "bsh.debug.script.file"
    const val CALLBACK_JAR_PROPERTY = "bsh.debug.callback.jar"

    /** Everything the IDE needs to drive a session, computed from the pom.xml under read access. */
    class Prepared(
        val pomFile: VirtualFile,
        val instrumented: String,
        /** `artifactId:tag` locating the configuration element the extension rewrites. */
        val target: String,
        /** 1-based snippet line -> 1-based pom.xml line. */
        val lineMap: Map<Int, Int>,
    )

    /** A socket + line map opened in getState(), awaiting the Maven process handler to start a session. */
    class Pending(
        val server: ServerSocket,
        val pomFile: VirtualFile,
        val lineMap: Map<Int, Int>,
    )

    private val pending = ConcurrentHashMap<Long, Pending>()

    fun register(executionId: Long, value: Pending) {
        pending[executionId] = value
    }

    fun consume(executionId: Long): Pending? = pending.remove(executionId)

    /**
     * Finds the first inline BeanShell script injected into [pomVFile], instruments it and builds
     * its line map. Returns null when the pom has no inline BeanShell (the run then proceeds as a
     * plain Maven build). Must be called under a read action.
     */
    fun prepare(project: Project, pomVFile: VirtualFile): Prepared? {
        val psi = PsiManager.getInstance(project).findFile(pomVFile) as? XmlFile ?: return null
        val manager = InjectedLanguageManager.getInstance(project)
        val hostDoc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return null

        for (xmlText in PsiTreeUtil.collectElementsOfType(psi, XmlText::class.java)) {
            val host = xmlText as? PsiLanguageInjectionHost ?: continue
            val bshFile = manager.getInjectedPsiFiles(host)?.firstNotNullOfOrNull { it.first as? BshFile } ?: continue
            val tag = xmlText.parentTag ?: continue
            val artifactId = enclosingPlugin(tag)?.findFirstSubTag("artifactId")?.value?.trimmedText ?: continue

            val instrumented = BshDebugInstrumenter.instrument(bshFile)
            val injectedDoc = bshFile.viewProvider.document ?: continue
            val lineMap = HashMap<Int, Int>()
            for (fragmentLine in 0 until injectedDoc.lineCount) {
                val injectedOffset = injectedDoc.getLineStartOffset(fragmentLine)
                val hostOffset = manager.injectedToHost(bshFile, injectedOffset).coerceIn(0, hostDoc.textLength)
                lineMap[fragmentLine + 1] = hostDoc.getLineNumber(hostOffset) + 1
            }
            return Prepared(pomVFile, instrumented, "$artifactId:${tag.name}", lineMap)
        }
        return null
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
