package cz.loplex.intellij.bsh.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import cz.loplex.intellij.bsh.psi.BshFormalParameter
import cz.loplex.intellij.bsh.psi.BshVariableDeclarator
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/** Removes an unused variable declarator or parameter, tidying up a trailing comma. */
class BshRemoveDeclarationFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove declaration"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val named = descriptor.psiElement?.parent ?: return
        when (named) {
            is BshVariableDeclarator -> {
                val declaration = named.parent
                val siblings = declaration?.node?.getChildren(null)
                    ?.count { it.elementType === E.VARIABLE_DECLARATOR } ?: 0
                if (siblings <= 1) declaration?.delete() else deleteWithComma(named)
            }
            is BshFormalParameter -> deleteWithComma(named)
        }
    }

    private fun deleteWithComma(element: PsiElement) {
        siblingComma(element)?.delete()
        element.delete()
    }

    private fun siblingComma(element: PsiElement): PsiElement? {
        var prev = element.prevSibling
        while (prev is PsiWhiteSpace) prev = prev.prevSibling
        if (prev != null && prev.node.elementType === BshTokenTypes.COMMA) return prev
        var next = element.nextSibling
        while (next is PsiWhiteSpace) next = next.nextSibling
        if (next != null && next.node.elementType === BshTokenTypes.COMMA) return next
        return null
    }
}

/** Deletes an unreachable statement. */
class BshRemoveStatementFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unreachable code"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        descriptor.psiElement?.delete()
    }
}
