package cz.loplex.intellij.bsh.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase

/**
 * Reference from a `.member` access on a statically-typed receiver to the Java
 * method or field it names, so Ctrl+Click navigates into the Java source.
 */
class BshMemberReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val typeName: String,
    private val memberName: String,
) : PsiReferenceBase<PsiElement>(element, rangeInElement) {

    override fun resolve(): PsiElement? =
        if (BshJavaSupport.isAvailable()) BshJavaResolver.resolveMember(element, typeName, memberName) else null

    override fun handleElementRename(newElementName: String): PsiElement = element
}
