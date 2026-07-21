package cz.loplex.intellij.bsh.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase

/**
 * Reference from a `.member` in a script to the method or field it names. The
 * target is produced lazily by [resolver] — either a BeanShell class member, or
 * a Java member found through static type propagation (see [BshChainResolver]).
 */
class BshMemberReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val resolver: () -> PsiElement?,
) : PsiReferenceBase<PsiElement>(element, rangeInElement) {

    override fun resolve(): PsiElement? = resolver()

    override fun handleElementRename(newElementName: String): PsiElement = element
}
