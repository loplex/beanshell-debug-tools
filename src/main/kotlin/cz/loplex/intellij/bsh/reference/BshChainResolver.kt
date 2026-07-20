package cz.loplex.intellij.bsh.reference

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Static type-propagation over a BeanShell primary expression, so Ctrl+Click
 * navigates into Java through a chain such as `results.iterator().next().value`.
 *
 * The receiver's type must be statically evident (typed variable/parameter, or
 * `= new Type()`, or a Java class prefix). From there each member's result type
 * — a method's return type or a field's type — feeds the next member.
 */
object BshChainResolver {

    private data class ChainStart(val startClass: PsiClass?, val memberStartIndex: Int, val isVariable: Boolean)

    // ---- References inside a dotted AmbiguousName --------------------------

    /** The class an AmbiguousName evaluates to after resolving all its segments. */
    fun ambiguousNameType(name: BshAmbiguousName): PsiClass? {
        val ids = identifiers(name)
        val start = chainStart(name, ids)
        var current = start.startClass ?: return null
        for (i in start.memberStartIndex until ids.size) {
            current = BshJavaResolver.memberType(current, ids[i].text) ?: return null
        }
        return current
    }

    /** Resolves segment [index] of an AmbiguousName as a Java member, if it is one. */
    fun resolveNameSegment(name: BshAmbiguousName, index: Int): PsiElement? {
        val ids = identifiers(name)
        val start = chainStart(name, ids)
        val startClass = start.startClass ?: return null
        if (index < start.memberStartIndex || index >= ids.size) return null
        var current = startClass
        for (i in start.memberStartIndex until index) {
            current = BshJavaResolver.memberType(current, ids[i].text) ?: return null
        }
        return BshJavaResolver.member(current, ids[index].text)
    }

    fun startInfo(name: BshAmbiguousName): Triple<PsiClass?, Int, Boolean> {
        val start = chainStart(name, identifiers(name))
        return Triple(start.startClass, start.memberStartIndex, start.isVariable)
    }

    // ---- References on a `.member` suffix ----------------------------------

    /** Resolves a PRIMARY_SUFFIX (`.member`) against the type flowing into it. */
    fun resolveSuffix(suffix: PsiElement): PsiElement? {
        val incoming = typeBeforeSuffix(suffix) ?: return null
        val member = suffix.node.findChildByType(BshTokenTypes.IDENTIFIER)?.text ?: return null
        return BshJavaResolver.member(incoming, member)
    }

    // ---- Internals ---------------------------------------------------------

    private fun chainStart(name: BshAmbiguousName, ids: List<ASTNode>): ChainStart {
        if (ids.isEmpty()) return ChainStart(null, -1, false)

        val variableType = BshTypeInference.variableType(name, ids[0].text)
        if (variableType != null) {
            val cls = BshJavaResolver.resolveClassPsi(name, variableType)
            if (cls != null) return ChainStart(cls, 1, true)
        }
        for (k in ids.size downTo 1) {
            val fqn = ids.subList(0, k).joinToString(".") { it.text }
            val cls = BshJavaResolver.resolveClassPsi(name, fqn)
            if (cls != null) return ChainStart(cls, k, false)
        }
        return ChainStart(null, -1, false)
    }

    /** The class of everything in the enclosing primary expression before [suffix]. */
    private fun typeBeforeSuffix(suffix: PsiElement): PsiClass? {
        val primary = suffix.parent ?: return null
        var current: PsiClass? = null
        var node = primary.node.firstChildNode
        while (node != null) {
            if (node.psi === suffix) break
            current = when (node.elementType) {
                E.AMBIGUOUS_NAME -> ambiguousNameType(node.psi as BshAmbiguousName)
                E.METHOD_INVOCATION -> {
                    val callName = node.findChildByType(E.AMBIGUOUS_NAME)?.psi as? BshAmbiguousName
                    if (callName != null) ambiguousNameType(callName) else null
                }
                E.ALLOCATION_EXPRESSION -> allocationClass(node)
                E.PRIMARY_SUFFIX -> {
                    val member = node.findChildByType(BshTokenTypes.IDENTIFIER)?.text
                    if (current != null && member != null) BshJavaResolver.memberType(current, member) else null
                }
                else -> current // whitespace/comments; anything else leaves the type unknown
            }
            node = node.treeNext
        }
        return current
    }

    private fun allocationClass(allocation: ASTNode): PsiClass? {
        val typeName = allocation.findChildByType(E.AMBIGUOUS_NAME)?.text ?: return null
        return BshJavaResolver.resolveClassPsi(allocation.psi, typeName.substringBefore('<'))
    }

    private fun identifiers(name: BshAmbiguousName): List<ASTNode> =
        name.node.getChildren(null).filter { it.elementType === BshTokenTypes.IDENTIFIER }
}
