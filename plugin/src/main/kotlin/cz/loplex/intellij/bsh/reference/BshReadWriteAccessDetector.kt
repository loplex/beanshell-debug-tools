package cz.loplex.intellij.bsh.reference

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshNamedElement

/**
 * Distinguishes read and write occurrences of a variable so the editor can
 * highlight them differently when the caret is on the symbol.
 */
class BshReadWriteAccessDetector : ReadWriteAccessDetector() {

    override fun isReadWriteAccessible(element: PsiElement): Boolean =
        element is BshNamedElement || element is BshAmbiguousName

    override fun isDeclarationWriteAccess(element: PsiElement): Boolean =
        element is BshAmbiguousName && BshScopes.isSimpleAssignmentTarget(element)

    override fun getReferenceAccess(referencedElement: PsiElement, reference: PsiReference): Access =
        getExpressionAccess(reference.element)

    override fun getExpressionAccess(expression: PsiElement): Access = when {
        expression is BshAmbiguousName && BshScopes.isSimpleAssignmentTarget(expression) -> Access.Write
        else -> Access.Read
    }
}
