package cz.loplex.intellij.bsh.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshNamedElement
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Name resolution for BeanShell within a single file.
 *
 * BeanShell is loosely typed and loosely scoped, so resolution is intentionally
 * lenient:
 *  - methods and classes are treated as file-global (a script may call a method
 *    defined later),
 *  - typed variables and parameters resolve to the declaration in the nearest
 *    enclosing lexical scope,
 *  - untyped variables have no declaration node, so the *first assignment* to a
 *    simple name in the nearest enclosing scope acts as the declaration.
 *
 * Unresolved names are simply left unresolved (no error), which matches the
 * language's dynamic nature.
 */
object BshResolver {

    private val SCOPE_TYPES = setOf(
        E.BLOCK, E.METHOD_DECLARATION, E.CLASS_DECLARATION,
        E.FOR_STATEMENT, E.ENHANCED_FOR_STATEMENT
    )

    fun resolve(reference: PsiElement, name: String): PsiElement? {
        if (name.isEmpty()) return null
        val isCall = reference.parent?.node?.elementType === E.METHOD_INVOCATION

        if (isCall) {
            fileMethod(reference, name)?.let { return it }
        }
        typedVariableInScope(reference, name)?.let { return it }
        untypedVariableInScope(reference, name)?.let { return it }
        fileMethod(reference, name)?.let { return it }
        fileClass(reference, name)?.let { return it }
        return null
    }

    private fun namedOfType(reference: PsiElement, name: String, type: com.intellij.psi.tree.IElementType): List<BshNamedElement> {
        val file = reference.containingFile ?: return emptyList()
        return PsiTreeUtil.collectElementsOfType(file, BshNamedElement::class.java)
            .filter { it.node.elementType === type && it.name == name }
    }

    private fun fileMethod(reference: PsiElement, name: String): PsiElement? =
        namedOfType(reference, name, E.METHOD_DECLARATION).firstOrNull()

    private fun fileClass(reference: PsiElement, name: String): PsiElement? =
        namedOfType(reference, name, E.CLASS_DECLARATION).firstOrNull()

    private fun typedVariableInScope(reference: PsiElement, name: String): PsiElement? {
        val file = reference.containingFile ?: return null
        val candidates = PsiTreeUtil.collectElementsOfType(file, BshNamedElement::class.java)
            .filter {
                (it.node.elementType === E.VARIABLE_DECLARATOR ||
                    it.node.elementType === E.FORMAL_PARAMETER) && it.name == name
            }
        return nearestVisible(reference, candidates)
    }

    private fun untypedVariableInScope(reference: PsiElement, name: String): PsiElement? {
        val file = reference.containingFile ?: return null
        val writes = PsiTreeUtil.collectElementsOfType(file, BshAmbiguousName::class.java)
            .filter { it.name == name && isSimpleAssignmentTarget(it) }

        val visible = writes.filter { PsiTreeUtil.isAncestor(scopeOf(it), reference, false) }
        if (visible.isEmpty()) return null

        // The declaration is the earliest assignment in the most tightly enclosing scope.
        val deepest = visible.maxOf { scopeDepth(scopeOf(it)) }
        return visible
            .filter { scopeDepth(scopeOf(it)) == deepest }
            .minByOrNull { it.textRange.startOffset }
    }

    private fun nearestVisible(reference: PsiElement, candidates: List<BshNamedElement>): PsiElement? =
        candidates
            .filter { PsiTreeUtil.isAncestor(scopeOf(it), reference, false) }
            .maxByOrNull { scopeDepth(scopeOf(it)) }

    /** True for `name = ...` where `name` is a single, unindexed identifier (an untyped variable). */
    private fun isSimpleAssignmentTarget(name: BshAmbiguousName): Boolean {
        // Single-segment name (not dotted).
        if (name.node.getChildren(null).count { it.elementType === BshTokenTypes.IDENTIFIER } != 1) return false

        val primary = name.parent ?: return false
        if (primary.node.elementType !== E.PRIMARY_EXPRESSION) return false
        // The primary must consist solely of this name (no `[i]` / `.field` suffixes).
        val significant = primary.node.getChildren(null).filter {
            it.elementType !== TokenType.WHITE_SPACE && it.elementType !in BshTokenTypes.COMMENTS.types
        }
        if (significant.size != 1 || significant[0] !== name.node) return false

        val assignment = primary.parent ?: return false
        if (assignment.node.elementType !== E.ASSIGNMENT) return false
        // Must be the left-hand side (first child) of the assignment.
        return assignment.node.firstChildNode === primary.node
    }

    private fun scopeOf(element: PsiElement): PsiElement {
        var current: PsiElement? = element.parent
        while (current != null && current !is PsiFile) {
            if (current.node?.elementType in SCOPE_TYPES) return current
            current = current.parent
        }
        return element.containingFile
    }

    private fun scopeDepth(scope: PsiElement): Int {
        var depth = 0
        var current: PsiElement? = scope
        while (current != null) { depth++; current = current.parent }
        return depth
    }
}
