package cz.loplex.intellij.bsh.debug

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import cz.loplex.intellij.bsh.BshFileType
import cz.loplex.intellij.bsh.psi.BshFile

/** Allows line breakpoints in BeanShell files and in inline BeanShell injected into a pom.xml. */
class BshLineBreakpointType :
    XLineBreakpointType<XBreakpointProperties<*>>(ID, "BeanShell Breakpoint") {

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.fileType == BshFileType) return true
        // Inline BeanShell injected into a pom.xml <script>/<condition>/<source>.
        if (file.name != "pom.xml") return false
        return hasInjectedBeanShellOnLine(project, file, line)
    }

    private fun hasInjectedBeanShellOnLine(project: Project, file: VirtualFile, line: Int): Boolean {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return false
        val document = psiFile.viewProvider.document ?: return false
        if (line < 0 || line >= document.lineCount) return false

        val manager = InjectedLanguageManager.getInstance(project)
        val lineEnd = document.getLineEndOffset(line)
        var offset = document.getLineStartOffset(line)
        while (offset <= lineEnd) {
            val element = psiFile.findElementAt(offset)
            val host = element?.let { PsiTreeUtil.getParentOfType(it, PsiLanguageInjectionHost::class.java, false) }
            if (host != null && isBeanShellInjected(manager, host)) return true
            offset = (element?.textRange?.endOffset ?: offset + 1).coerceAtLeast(offset + 1)
        }
        return false
    }

    private fun isBeanShellInjected(manager: InjectedLanguageManager, host: PsiLanguageInjectionHost): Boolean =
        manager.getInjectedPsiFiles(host)?.any { it.first is BshFile } == true

    companion object {
        const val ID = "bsh-line"
    }
}
