package cz.loplex.intellij.bsh.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import cz.loplex.intellij.bsh.BshFileType

/** Creates BeanShell PSI fragments used when renaming declarations and references. */
object BshPsiFactory {

    fun createFile(project: Project, text: String): BshFile =
        PsiFileFactory.getInstance(project)
            .createFileFromText("dummy.bsh", BshFileType, text) as BshFile

    /** Returns a single IDENTIFIER leaf carrying [name], for use with PSI replace. */
    fun createIdentifier(project: Project, name: String): PsiElement {
        val file = createFile(project, "$name = 0;")
        return PsiTreeUtil.collectElements(file) { it.node.elementType === BshTokenTypes.IDENTIFIER }
            .first()
    }
}
