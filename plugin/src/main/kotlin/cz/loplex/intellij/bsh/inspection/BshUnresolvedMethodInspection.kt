package cz.loplex.intellij.bsh.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.reference.BshResolver
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Flags calls to methods that cannot be resolved anywhere in the project.
 *
 * Disabled by default: BeanShell scripts routinely call Java library methods and
 * built-in commands (e.g. `print`) that this plugin does not model, so enabling
 * it is opt-in.
 */
class BshUnresolvedMethodInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is BshAmbiguousName) return
                if (element.parent?.node?.elementType !== E.METHOD_INVOCATION) return

                val identifier = element.node.findChildByType(BshTokenTypes.IDENTIFIER) ?: return
                // Only simple, single-segment call targets.
                if (element.node.getChildren(null).count { it.elementType === BshTokenTypes.IDENTIFIER } != 1) return

                if (BshResolver.resolve(element, identifier.text) == null) {
                    holder.registerProblem(
                        identifier.psi,
                        "Cannot resolve method '${identifier.text}'",
                        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                    )
                }
            }
        }
}
