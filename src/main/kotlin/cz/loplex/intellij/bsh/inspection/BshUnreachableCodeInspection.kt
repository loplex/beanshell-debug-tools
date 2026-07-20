package cz.loplex.intellij.bsh.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Flags statements that follow an unconditional control-flow exit
 * (`return` / `break` / `continue` / `throw`) within the same block.
 */
class BshUnreachableCodeInspection : LocalInspectionTool() {

    private val terminals = setOf(E.RETURN_STATEMENT, E.THROW_STATEMENT)

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.node.elementType !== E.BLOCK) return

                var terminalSeen = false
                var child: ASTNode? = element.node.firstChildNode
                while (child != null) {
                    if (isStatement(child)) {
                        if (terminalSeen) {
                            holder.registerProblem(
                                child.psi,
                                "Unreachable code",
                                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                            )
                            return
                        }
                        if (child.elementType in terminals) terminalSeen = true
                    }
                    child = child.treeNext
                }
            }
        }

    /** A statement / declaration is any composite child of the block. */
    private fun isStatement(node: ASTNode): Boolean = node.firstChildNode != null
}
