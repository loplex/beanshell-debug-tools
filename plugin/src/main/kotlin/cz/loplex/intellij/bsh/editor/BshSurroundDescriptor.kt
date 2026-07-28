package cz.loplex.intellij.bsh.editor

import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/** Provides "Surround With" (Ctrl+Alt+T) for BeanShell statements. */
class BshSurroundDescriptor : SurroundDescriptor {

    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        val start = file.findElementAt(startOffset) ?: return PsiElement.EMPTY_ARRAY
        val container = enclosingContainer(start)
        val result = ArrayList<PsiElement>()
        var child: PsiElement? = container.firstChild
        while (child != null) {
            val range = child.textRange
            // Statements are composite nodes; also pull in trailing ';' tokens so a
            // selected expression statement is surrounded together with its semicolon.
            val isStatement = (child.firstChild != null && child !is PsiWhiteSpace && child !is PsiComment) ||
                child.node?.elementType === BshTokenTypes.SEMICOLON
            if (isStatement && range.startOffset < endOffset && range.endOffset > startOffset) {
                result.add(child)
            }
            child = child.nextSibling
        }
        return result.toTypedArray()
    }

    override fun getSurrounders(): Array<Surrounder> = arrayOf(
        BshIfSurrounder(),
        BshWhileSurrounder(),
        BshTrySurrounder(),
    )

    override fun isExclusive(): Boolean = false

    private fun enclosingContainer(element: PsiElement): PsiElement {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            if (current.node?.elementType === E.BLOCK) return current
            current = current.parent
        }
        return element.containingFile
    }
}
