package cz.loplex.intellij.bsh.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase

/**
 * Reference from a `.member` in a script to the Java method or field it names,
 * resolved (lazily) through static type propagation. See [BshChainResolver].
 */
class BshMemberReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val resolver: () -> PsiElement?,
) : PsiReferenceBase<PsiElement>(element, rangeInElement) {

    override fun resolve(): PsiElement? = if (BshJavaSupport.isAvailable()) resolver() else null

    override fun handleElementRename(newElementName: String): PsiElement = element
}
