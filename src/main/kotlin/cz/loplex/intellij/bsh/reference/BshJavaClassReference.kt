package cz.loplex.intellij.bsh.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase

/**
 * Reference from a class name used in a BeanShell script to its Java PSI class,
 * so Ctrl+Click / Go to Declaration jumps into the Java source.
 */
class BshJavaClassReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val className: String,
) : PsiReferenceBase<PsiElement>(element, rangeInElement) {

    override fun resolve(): PsiElement? =
        if (BshJavaSupport.isAvailable()) BshJavaResolver.resolveClass(element, className) else null

    // Renaming a Java class from a BeanShell usage is out of scope.
    override fun handleElementRename(newElementName: String): PsiElement = element
}
