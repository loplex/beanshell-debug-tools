package cz.loplex.intellij.bsh.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/** Shared lexical-scope helpers used by name resolution and completion. */
object BshScopes {

    val SCOPE_TYPES = setOf(
        E.BLOCK, E.METHOD_DECLARATION, E.CLASS_DECLARATION,
        E.FOR_STATEMENT, E.ENHANCED_FOR_STATEMENT
    )

    /** The nearest enclosing lexical scope (block, method, class, for) or the file. */
    fun scopeOf(element: PsiElement): PsiElement {
        var current: PsiElement? = element.parent
        while (current != null && current !is PsiFile) {
            if (current.node?.elementType in SCOPE_TYPES) return current
            current = current.parent
        }
        return element.containingFile
    }

    fun scopeDepth(scope: PsiElement): Int {
        var depth = 0
        var current: PsiElement? = scope
        while (current != null) { depth++; current = current.parent }
        return depth
    }

    /** True for `name = ...` where `name` is a single, unindexed identifier (an untyped variable). */
    fun isSimpleAssignmentTarget(name: BshAmbiguousName): Boolean {
        if (name.node.getChildren(null).count { it.elementType === BshTokenTypes.IDENTIFIER } != 1) return false

        val primary = name.parent ?: return false
        if (primary.node.elementType !== E.PRIMARY_EXPRESSION) return false
        val significant = primary.node.getChildren(null).filter {
            it.elementType !== TokenType.WHITE_SPACE && it.elementType !in BshTokenTypes.COMMENTS.types
        }
        if (significant.size != 1 || significant[0] !== name.node) return false

        val assignment = primary.parent ?: return false
        return assignment.node.elementType === E.ASSIGNMENT && assignment.node.firstChildNode === primary.node
    }
}
