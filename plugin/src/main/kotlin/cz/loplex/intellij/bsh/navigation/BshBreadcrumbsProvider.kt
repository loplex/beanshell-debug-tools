package cz.loplex.intellij.bsh.navigation

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import cz.loplex.intellij.bsh.BshLanguage
import cz.loplex.intellij.bsh.psi.BshClassDeclaration
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshNamedElement

/** Shows the enclosing class / method chain in the editor breadcrumbs bar. */
class BshBreadcrumbsProvider : BreadcrumbsProvider {

    override fun getLanguages(): Array<Language> = arrayOf(BshLanguage)

    override fun acceptElement(element: PsiElement): Boolean =
        element is BshMethodDeclaration || element is BshClassDeclaration

    override fun getElementInfo(element: PsiElement): String =
        (element as? BshNamedElement)?.name ?: "?"

    override fun getElementTooltip(element: PsiElement): String? = when (element) {
        is BshMethodDeclaration -> "method"
        is BshClassDeclaration -> "class"
        else -> null
    }
}
