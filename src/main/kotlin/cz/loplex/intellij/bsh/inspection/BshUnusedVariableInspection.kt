package cz.loplex.intellij.bsh.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import cz.loplex.intellij.bsh.psi.BshFormalParameter
import cz.loplex.intellij.bsh.psi.BshNamedElement
import cz.loplex.intellij.bsh.psi.BshVariableDeclarator

/**
 * Flags typed variables and parameters that are declared but never read within
 * the file. Untyped variables are intentionally ignored: every occurrence is an
 * assignment target, so "never used" is not well defined for them.
 */
class BshUnusedVariableInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is BshVariableDeclarator && element !is BshFormalParameter) return
                val named = element as BshNamedElement
                val identifier = named.nameIdentifier ?: return
                val file = element.containingFile ?: return

                val used = ReferencesSearch
                    .search(named, LocalSearchScope(file))
                    .findFirst() != null
                if (!used) {
                    val kind = if (element is BshFormalParameter) "Parameter" else "Variable"
                    holder.registerProblem(
                        identifier,
                        "$kind '${named.name}' is never used",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    )
                }
            }
        }
}
