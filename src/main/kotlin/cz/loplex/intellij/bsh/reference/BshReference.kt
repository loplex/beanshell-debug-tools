package cz.loplex.intellij.bsh.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import cz.loplex.intellij.bsh.psi.BshPsiFactory
import cz.loplex.intellij.bsh.psi.BshTokenTypes

/**
 * Reference from a name usage (the first identifier of an `AmbiguousName`) to
 * its declaration. Dotted names resolve only their first segment; the remaining
 * segments are field accesses that cannot be resolved without type information.
 */
class BshReference(element: PsiElement, rangeInElement: TextRange) :
    PsiReferenceBase<PsiElement>(element, rangeInElement) {

    override fun resolve(): PsiElement? = BshResolver.resolve(element, value)

    override fun handleElementRename(newElementName: String): PsiElement {
        val identifier = element.node.findChildByType(BshTokenTypes.IDENTIFIER)
        identifier?.psi?.replace(BshPsiFactory.createIdentifier(element.project, newElementName))
        return element
    }
}
